package uk.xmlangel.googledrivesync.sync

/**
 * Global sync exclusion rules applied across sync/verification flows.
 */
enum class SyncExclusionType { FILE, DIRECTORY, PATTERN }

object SyncExclusions {
    data class Rule(
        val type: SyncExclusionType,
        val value: String,
        val source: String,
        val reason: String
    ) {
        fun toStorageToken(): String = "${type.name.lowercase()}:$value"
    }

    private val defaultRules = listOf(
        Rule(
            type = SyncExclusionType.FILE,
            value = ".obsidian/workspace.json",
            source = "system",
            reason = "기기별 UI 상태 파일로 충돌이 잦아 동기화에서 제외"
        )
    )

    fun defaults(): List<Rule> = defaultRules

    fun parseUserRules(tokens: Set<String>): List<Rule> {
        return tokens.mapNotNull { token ->
            parseToken(token)?.copy(source = "user", reason = "사용자 추가 제외 규칙")
        }.distinctBy { "${it.type}:${it.value}" }
    }

    fun allWithUser(userRuleTokens: Set<String>): List<Rule> {
        return defaultRules + parseUserRules(userRuleTokens)
    }

    fun isDefaultRelativePath(path: String): Boolean {
        val normalized = normalizeRelativePath(path)
        return defaultRules.any { it.type == SyncExclusionType.FILE && it.value == normalized }
    }

    fun isExcludedRelativePath(path: String, userRuleTokens: Set<String> = emptySet()): Boolean {
        val normalized = normalizeRelativePath(path)
        val allRules = defaultRules + parseUserRules(userRuleTokens)
        return allRules.any { matches(normalized, it) }
    }

    fun isExcludedAbsolutePath(path: String, userRuleTokens: Set<String> = emptySet()): Boolean {
        val normalized = path.replace('\\', '/')
        val allRules = defaultRules + parseUserRules(userRuleTokens)
        return allRules.any { rule ->
            val rel = rule.value
            when (rule.type) {
                SyncExclusionType.FILE -> normalized.endsWith("/$rel") || normalized == rel
                SyncExclusionType.DIRECTORY -> normalized.endsWith("/$rel") || normalized.contains("/$rel/")
                SyncExclusionType.PATTERN -> matchGlob(normalized.substringAfterLast('/'), rel) || matchGlob(normalized, rel)
            }
        }
    }

    fun normalizeRelativePath(path: String): String {
        return path.replace('\\', '/').trim().trimStart('/')
    }

    fun buildUserRuleToken(type: SyncExclusionType, rawValue: String): String? {
        val normalized = normalizeRelativePath(rawValue)
        if (normalized.isBlank()) return null
        return "${type.name.lowercase()}:$normalized"
    }

    private fun parseToken(token: String): Rule? {
        val sep = token.indexOf(':')
        if (sep <= 0) return null
        val typeRaw = token.substring(0, sep).lowercase()
        val value = normalizeRelativePath(token.substring(sep + 1))
        if (value.isBlank()) return null
        val type = when (typeRaw) {
            "file" -> SyncExclusionType.FILE
            "directory", "dir", "folder" -> SyncExclusionType.DIRECTORY
            "pattern", "glob" -> SyncExclusionType.PATTERN
            else -> return null
        }
        return Rule(type = type, value = value, source = "user", reason = "사용자 추가 제외 규칙")
    }

    private fun matches(relativePath: String, rule: Rule): Boolean {
        return when (rule.type) {
            SyncExclusionType.FILE -> relativePath == rule.value
            SyncExclusionType.DIRECTORY -> relativePath == rule.value || relativePath.startsWith("${rule.value}/")
            SyncExclusionType.PATTERN -> matchGlob(relativePath, rule.value)
        }
    }

    private fun matchGlob(input: String, glob: String): Boolean {
        val regex = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^$regex$").matches(input)
    }
}
