import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

val requiredSecretIgnoreRules = setOf(
    "local.properties",
    "app/google-services.json",
    "app/src/*/google-services.json",
    "secrets.properties",
    ".env",
    ".env.*",
    "*.jks",
    "*.keystore",
    "*.p12",
    "*.pfx",
    "*.pem",
    "*.key",
    "*.p8",
    "*.der",
    "credentials*.json",
    "service-account*.json",
    "firebase-adminsdk*.json",
    "*.apk",
    "*.aab",
    "*.idsig"
)

val workspaceSecretScanFile = layout.buildDirectory.file(
    "reports/release-security/workspace-secret-scan.txt"
)

fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

fun gitBytes(vararg arguments: String): ByteArray {
    val process = ProcessBuilder(listOf("git") + arguments)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readBytes()
    if (process.waitFor() != 0) {
        throw GradleException("Git command failed while inspecting commit candidates: git ${arguments.joinToString(" ")}")
    }
    return output
}

fun nullSeparatedGitPaths(vararg arguments: String): List<String> {
    return gitBytes(*arguments)
        .toString(Charsets.UTF_8)
        .split('\u0000')
        .filter(String::isNotBlank)
}

val verifyWorkspaceSecurity = tasks.register("verifyWorkspaceSecurity") {
    group = "verification"
    description = "Scans reachable Git history, index, tracked worktree, and untracked commit candidates for high-confidence secrets."
    outputs.file(workspaceSecretScanFile)
    outputs.upToDateWhen { false }

    doLast {
        val ignoreFile = rootProject.file(".gitignore")
        val ignoreRules = ignoreFile.takeIf(File::exists)
            ?.readLines()
            ?.map(String::trim)
            ?.filter { it.isNotBlank() && !it.startsWith("#") }
            ?.toSet()
            .orEmpty()
        val missingIgnoreRules = requiredSecretIgnoreRules - ignoreRules
        if (missingIgnoreRules.isNotEmpty()) {
            throw GradleException(
                "Workspace security ignore rules missing: ${missingIgnoreRules.sorted().joinToString()}"
            )
        }

        val stagedEntries = gitBytes("ls-files", "--stage", "-z")
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotBlank)
            .map { entry ->
                val metadata = entry.substringBefore('\t').split(' ')
                if (metadata.size != 3 || metadata[2] != "0") {
                    throw GradleException("Workspace security requires a conflict-free Git index.")
                }
                Triple(entry.substringAfter('\t'), metadata[0], metadata[1])
            }
            .sortedBy { it.first }
        val worktreeCandidatePaths = nullSeparatedGitPaths(
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z"
        ).sorted()
        val candidatePaths = (stagedEntries.map { it.first } + worktreeCandidatePaths).toSortedSet()

        val sensitivePath = Regex(
            "(^|/)(local\\.properties|google-services\\.json|secrets\\.properties|\\.env(\\..+)?|" +
                "\\.(npmrc|pypirc|netrc)|id_(rsa|dsa|ecdsa|ed25519)|" +
                "[^/]+\\.(jks|keystore|p12|pfx|pem|key|p8|der|kdbx|age|apk|aab|idsig)|" +
                "(credentials|service-account|firebase-adminsdk)[^/]*\\.json)$",
            RegexOption.IGNORE_CASE
        )
        val sensitiveCommitPaths = candidatePaths.filter(sensitivePath::containsMatchIn)
        if (sensitiveCommitPaths.isNotEmpty()) {
            throw GradleException(
                "Sensitive files are eligible for commit: ${sensitiveCommitPaths.sorted().joinToString()}"
            )
        }

        val detectors = linkedMapOf(
            "private key" to Regex("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY(?: BLOCK)?-----"),
            "credential URL" to Regex("https?://[^\\s/@:]+:[^\\s/@]+@", RegexOption.IGNORE_CASE),
            "remote root credential" to Regex(
                "(?:sshpass\\s+-p|root@[0-9]{1,3}(?:\\.[0-9]{1,3}){3})",
                RegexOption.IGNORE_CASE
            ),
            "remote root login block" to Regex(
                "(?im)\\b[0-9]{1,3}(?:\\.[0-9]{1,3}){3}\\b[ \\t]*\\r?\\n" +
                    "[ \\t]*root[ \\t]*\\r?\\n[ \\t]*[^\\s<>$]{10,}[ \\t]*$"
            ),
            "AWS access key" to Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            "Google API key" to Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            "Google OAuth client secret" to Regex("\\bGOCSPX-[0-9A-Za-z_-]{24,}\\b"),
            "GitHub token" to Regex(
                "\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{40,255})\\b"
            ),
            "GitLab token" to Regex("\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
            "Slack token" to Regex("\\bxox[baprs]-[0-9A-Za-z-]{20,}\\b"),
            "Stripe live key" to Regex("\\b(?:sk|rk)_live_[0-9A-Za-z]{16,}\\b"),
            "OpenAI API key" to Regex("\\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}\\b"),
            "npm token" to Regex("\\bnpm_[A-Za-z0-9]{36}\\b"),
            "PyPI token" to Regex("\\bpypi-AgEIcHlwaS5vcmc[A-Za-z0-9_-]{20,}\\b"),
            "SendGrid API key" to Regex("\\bSG\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{32,}\\b"),
            "Firebase server key" to Regex("\\bAAAA[A-Za-z0-9_-]{7,}:[A-Za-z0-9_-]{80,}\\b")
        )
        val releaseValueAssignment = Regex(
            "(?im)^[ \\t]*(?:export[ \\t]+)?(V_SLOT_(?:PRIVACY_POLICY_URL|APPMETRICA_API_KEY|" +
                "RELEASE_STORE_FILE|RELEASE_STORE_PASSWORD|RELEASE_KEY_ALIAS|RELEASE_KEY_PASSWORD))[ \\t]*" +
                "[=:][ \\t]*[\\\"']?([^\\s\\\"'<>$][^#\\r\\n]*)$"
        )
        val highEntropyAssignment = Regex(
            "(?i)(?:api[_-]?key|client[_-]?secret|secret[_-]?access[_-]?key|access[_-]?token|" +
                "auth[_-]?token|private[_-]?token|password)[\\\"']?[ \\t]*[=:][ \\t]*" +
                "[\\\"']?([A-Za-z0-9_./+=:@-]{12,})[\\\"']?"
        )
        val encodedKeystoreAssignment = Regex(
            "(?i)(?:keystore|private[_-]?key|credentials|service[_-]?account)[A-Za-z0-9_-]*" +
                "(?:_BASE64|Base64)[\\\"']?[ \\t]*[=:][ \\t]*[\\\"']?" +
                "([A-Za-z0-9+/]{64,}={0,2})[\\\"']?"
        )
        val placeholderMarkers = listOf(
            "example", "placeholder", "dummy", "fake", "your-", "changeme", "redacted", "test-token"
        )
        val contentViolations = sortedSetOf<String>()
        val snapshotDigest = MessageDigest.getInstance("SHA-256")
        var scannedContentCount = 0
        var scannedByteCount = 0L
        val maxCandidateBytes = 64L * 1024L * 1024L

        fun scan(relativePath: String, origin: String, bytes: ByteArray) {
            snapshotDigest.update(origin.toByteArray(Charsets.UTF_8))
            snapshotDigest.update(0.toByte())
            snapshotDigest.update(relativePath.toByteArray(Charsets.UTF_8))
            snapshotDigest.update(0.toByte())
            snapshotDigest.update(sha256(bytes).toByteArray(Charsets.US_ASCII))
            snapshotDigest.update('\n'.code.toByte())
            scannedContentCount += 1
            scannedByteCount += bytes.size
            val text = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    bytes.toString(Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    bytes.toString(Charsets.UTF_16BE)
                bytes.any { it == 0.toByte() } -> {
                    val evenZeroCount = bytes.indices.count { index ->
                        index % 2 == 0 && bytes[index] == 0.toByte()
                    }
                    val oddZeroCount = bytes.indices.count { index ->
                        index % 2 == 1 && bytes[index] == 0.toByte()
                    }
                    val halfSize = (bytes.size / 2).coerceAtLeast(1)
                    when {
                        oddZeroCount * 4 >= halfSize * 3 -> bytes.toString(Charsets.UTF_16LE)
                        evenZeroCount * 4 >= halfSize * 3 -> bytes.toString(Charsets.UTF_16BE)
                        else -> buildString {
                            var previousWasSeparator = true
                            bytes.forEach { byte ->
                                val value = byte.toInt() and 0xFF
                                val printable = value in 0x20..0x7E ||
                                    value == '\n'.code ||
                                    value == '\r'.code
                                if (printable) {
                                    append(value.toChar())
                                    previousWasSeparator = false
                                } else if (!previousWasSeparator) {
                                    append('\n')
                                    previousWasSeparator = true
                                }
                            }
                        }
                    }
                }
                else -> bytes.toString(Charsets.UTF_8)
            }
            val reasons = buildList {
                detectors.forEach { (reason, detector) ->
                    if (detector.containsMatchIn(text)) add(reason)
                }
                if (releaseValueAssignment.containsMatchIn(text)) add("inline production value")
                val leakedAssignment = highEntropyAssignment.findAll(text).any { match ->
                    val value = match.groupValues[1]
                    val normalized = value.lowercase()
                    val characterClasses = listOf(
                        value.any(Char::isLowerCase),
                        value.any(Char::isUpperCase),
                        value.any(Char::isDigit),
                        value.any { !it.isLetterOrDigit() }
                    ).count { it }
                    value.toSet().size >= 8 &&
                        value.any(Char::isDigit) &&
                        characterClasses >= 2 &&
                        placeholderMarkers.none(normalized::contains)
                }
                if (leakedAssignment) add("high-entropy credential assignment")
                val encodedKeystore = encodedKeystoreAssignment.findAll(text).any { match ->
                    val decoded = runCatching {
                        Base64.getDecoder().decode(match.groupValues[1])
                    }.getOrNull() ?: return@any false
                    decoded.size >= 4 && decoded.copyOfRange(0, 4).contentEquals(
                        byteArrayOf(
                            0xFE.toByte(),
                            0xED.toByte(),
                            0xFE.toByte(),
                            0xED.toByte()
                        )
                    )
                }
                if (encodedKeystore) add("base64-encoded JKS keystore")
            }
            if (reasons.isNotEmpty()) {
                contentViolations += "$relativePath@$origin (${reasons.distinct().sorted().joinToString()})"
            }
        }

        if (stagedEntries.isNotEmpty()) {
            val catFile = ProcessBuilder("git", "cat-file", "--batch")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val request = catFile.outputStream.bufferedWriter(Charsets.UTF_8)
            val response = catFile.inputStream.buffered()
            stagedEntries.forEach { (relativePath, _, objectId) ->
                request.write(objectId)
                request.newLine()
                request.flush()
                val header = buildString {
                    while (true) {
                        val value = response.read()
                        if (value < 0 || value == '\n'.code) break
                        append(value.toChar())
                    }
                }
                val parts = header.split(' ')
                val byteCount = parts.getOrNull(2)?.toLongOrNull()
                    ?: throw GradleException("Workspace security could not read staged blob $relativePath.")
                if (parts.getOrNull(1) != "blob" || byteCount > maxCandidateBytes) {
                    catFile.destroyForcibly()
                    throw GradleException(
                        "Workspace security cannot scan staged candidate $relativePath ($byteCount bytes)."
                    )
                }
                val bytes = response.readNBytes(byteCount.toInt())
                if (bytes.size != byteCount.toInt() || response.read() != '\n'.code) {
                    catFile.destroyForcibly()
                    throw GradleException("Workspace security received an incomplete staged blob for $relativePath.")
                }
                scan(relativePath, "index", bytes)
            }
            request.close()
            if (catFile.waitFor() != 0) {
                throw GradleException("Workspace security could not inspect staged Git blobs.")
            }
        }

        worktreeCandidatePaths.forEach { relativePath ->
            val file = rootProject.file(relativePath)
            if (!file.isFile) return@forEach
            if (file.length() > maxCandidateBytes) {
                throw GradleException(
                    "Workspace security cannot scan worktree candidate $relativePath (${file.length()} bytes)."
                )
            }
            val bytes = runCatching(file::readBytes).getOrElse {
                throw GradleException("Workspace security could not read worktree candidate $relativePath.", it)
            }
            scan(relativePath, "worktree", bytes)
        }

        var historyBlobCount = 0
        var historyMessageCount = 0
        var tagMessageCount = 0
        val hasReachableHistory = runCatching {
            gitBytes("rev-parse", "--verify", "HEAD")
        }.isSuccess
        if (hasReachableHistory) {
            val historyObjects = gitBytes(
                "rev-list",
                "--objects",
                "--branches",
                "--remotes",
                "--tags"
            )
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .map { line ->
                    val objectId = line.substringBefore(' ')
                    val historicalPath = line.substringAfter(' ', missingDelimiterValue = objectId)
                    objectId to historicalPath
                }
                .distinctBy { (objectId, _) -> objectId }
                .toList()
            val sensitiveHistoryPaths = historyObjects
                .map { (_, historicalPath) -> historicalPath }
                .filter(sensitivePath::containsMatchIn)
                .toSortedSet()
            sensitiveHistoryPaths.forEach { historicalPath ->
                contentViolations += "$historicalPath@history (sensitive path)"
            }

            val catFile = ProcessBuilder("git", "cat-file", "--batch")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val request = catFile.outputStream.bufferedWriter(Charsets.UTF_8)
            val response = catFile.inputStream.buffered()
            historyObjects.forEach { (objectId, historicalPath) ->
                request.write(objectId)
                request.newLine()
                request.flush()
                val header = buildString {
                    while (true) {
                        val value = response.read()
                        if (value < 0 || value == '\n'.code) break
                        append(value.toChar())
                    }
                }
                val parts = header.split(' ')
                val objectType = parts.getOrNull(1)
                val byteCount = parts.getOrNull(2)?.toLongOrNull()
                    ?: throw GradleException("Workspace security could not read historical Git object $objectId.")
                if (byteCount !in 0L..maxCandidateBytes) {
                    catFile.destroyForcibly()
                    throw GradleException(
                        "Workspace security cannot scan historical Git object $objectId ($byteCount bytes)."
                    )
                }
                val bytes = response.readNBytes(byteCount.toInt())
                if (bytes.size != byteCount.toInt() || response.read() != '\n'.code) {
                    catFile.destroyForcibly()
                    throw GradleException("Workspace security received an incomplete historical Git object $objectId.")
                }
                if (objectType == "blob") {
                    scan(historicalPath, "history:$objectId", bytes)
                    historyBlobCount += 1
                }
            }
            request.close()
            if (catFile.waitFor() != 0) {
                throw GradleException("Workspace security could not inspect reachable Git history.")
            }

            fun scanGitMessages(arguments: Array<String>, originPrefix: String): Int {
                val fields = gitBytes(*arguments)
                    .toString(Charsets.UTF_8)
                    .split('\u0000')
                var count = 0
                var index = 0
                while (index + 1 < fields.size) {
                    val objectId = fields[index].trim()
                    val message = fields[index + 1].trim()
                    if (objectId.matches(Regex("[0-9a-fA-F]{40,64}")) && message.isNotBlank()) {
                        scan(
                            "git-message",
                            "$originPrefix:$objectId",
                            message.toByteArray(Charsets.UTF_8)
                        )
                        count += 1
                    }
                    index += 2
                }
                return count
            }
            historyMessageCount = scanGitMessages(
                arrayOf(
                    "log",
                    "--branches",
                    "--remotes",
                    "--tags",
                    "--format=%H%x00%B%x00"
                ),
                "commit-message"
            )
            tagMessageCount = scanGitMessages(
                arrayOf("for-each-ref", "refs/tags", "--format=%(objectname)%00%(contents)%00"),
                "tag-message"
            )
        }

        val output = workspaceSecretScanFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("schema=workspace-secret-scan-v3")
                appendLine("scope=reachable-branches-remotes-tags+git-index+tracked-worktree+untracked-nonignored")
                appendLine("history-status=${if (hasReachableHistory) "COMPLETE" else "UNAVAILABLE"}")
                appendLine("candidate-path-count=${candidatePaths.size}")
                appendLine("staged-blob-count=${stagedEntries.size}")
                appendLine("history-blob-count=$historyBlobCount")
                appendLine("history-message-count=$historyMessageCount")
                appendLine("tag-message-count=$tagMessageCount")
                appendLine("scanned-text-content-count=$scannedContentCount")
                appendLine("scanned-text-byte-count=$scannedByteCount")
                appendLine(
                    "candidate-snapshot-sha256=" + snapshotDigest.digest().joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 0xFF)
                    }
                )
                appendLine("detectors=${(detectors.keys + "inline production value" + "high-entropy credential assignment" + "base64-encoded JKS keystore").sorted().joinToString(",")}")
                appendLine(
                    "status=" + when {
                        contentViolations.isNotEmpty() -> "FAIL"
                        !hasReachableHistory -> "INCOMPLETE"
                        else -> "PASS"
                    }
                )
                contentViolations.forEach { appendLine("violation=$it") }
            },
            Charsets.UTF_8
        )
        if (contentViolations.isNotEmpty()) {
            throw GradleException(
                "Workspace security found sensitive commit content: ${contentViolations.sorted().joinToString()}"
            )
        }
    }
}

val releaseSecurityEvidenceFile = layout.buildDirectory.file(
    "reports/release-security/release-security-evidence.txt"
)
val releaseOsvScanEvidenceFile = layout.buildDirectory.file(
    "reports/release-security/osv-scan-evidence.txt"
)

val verifyReleaseOsvScanEvidence = tasks.register("verifyReleaseOsvScanEvidence") {
    group = "verification"
    description = "Validates the successful protected-CI OSV scan result bound to this release commit and inventory."
    val sourcePath = System.getenv("V_SLOT_OSV_SCAN_EVIDENCE_FILE").orEmpty()
    if (sourcePath.isNotBlank()) inputs.file(file(sourcePath))
    inputs.file(layout.projectDirectory.file("osv-scanner-custom.json"))
    outputs.file(releaseOsvScanEvidenceFile)
    outputs.upToDateWhen { false }

    doLast {
        val source = sourcePath.takeIf(String::isNotBlank)?.let(::file)
            ?: throw GradleException("Release requires V_SLOT_OSV_SCAN_EVIDENCE_FILE from protected CI.")
        if (!source.isFile || source.length() !in 1L..16_384L) {
            throw GradleException("Release OSV scan evidence must be a bounded regular file.")
        }
        val parsedFields = source.readLines(Charsets.UTF_8)
            .filter(String::isNotBlank)
            .map { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) throw GradleException("Release OSV scan evidence has an invalid field.")
                line.substring(0, separator) to line.substring(separator + 1)
            }
        if (parsedFields.map { it.first }.toSet().size != parsedFields.size) {
            throw GradleException("Release OSV scan evidence contains duplicate fields.")
        }
        val fields = parsedFields.toMap()
        val expectedKeys = setOf(
            "schema",
            "scanner-action-sha",
            "result",
            "commit",
            "inventory-sha256",
            "github-run-id",
            "github-run-attempt"
        )
        if (fields.keys != expectedKeys) {
            throw GradleException("Release OSV scan evidence requires the exact schema-v1 fields.")
        }
        val expectedScannerActionSha = "9a498708959aeaef5ef730655706c5a1df1edbc2"
        if (fields["schema"] != "v-slot-osv-scan-evidence-v1" ||
            fields["scanner-action-sha"] != expectedScannerActionSha ||
            fields["result"] != "success"
        ) {
            throw GradleException("Release OSV scan evidence does not prove the pinned scanner succeeded.")
        }
        val head = gitBytes("rev-parse", "--verify", "HEAD")
            .toString(Charsets.UTF_8).trim().lowercase()
        if (fields["commit"]?.lowercase() != head) {
            throw GradleException("Release OSV scan evidence commit does not match release HEAD.")
        }
        val inventory = rootProject.file("osv-scanner-custom.json")
        if (fields["inventory-sha256"]?.lowercase() != sha256(inventory.readBytes())) {
            throw GradleException("Release OSV scan evidence inventory does not match reviewed dependencies.")
        }
        if (fields["github-run-id"]?.toLongOrNull()?.let { it > 0L } != true ||
            fields["github-run-attempt"]?.toLongOrNull()?.let { it > 0L } != true
        ) {
            throw GradleException("Release OSV scan evidence requires valid GitHub run identity.")
        }
        val output = releaseOsvScanEvidenceFile.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(source.readBytes())
    }
}

val verifyReleaseSecurityEvidence = tasks.register("verifyReleaseSecurityEvidence") {
    group = "verification"
    description = "Produces reproducible evidence for secret scanning and release dependency/license coverage."
    dependsOn(
        verifyWorkspaceSecurity,
        ":app:verifyReleaseDependencyLicenses",
        ":app:verifyReleaseOsvInventory"
    )
    val dependencyInventory = project(":app").layout.buildDirectory.file(
        "reports/release-security/release-runtime-classpath.tsv"
    )
    val licenseEvidence = project(":app").layout.buildDirectory.file(
        "reports/release-security/release-license-evidence.txt"
    )
    val releaseOsvInventory = layout.projectDirectory.file("osv-scanner-custom.json")
    inputs.files(workspaceSecretScanFile, dependencyInventory, licenseEvidence, releaseOsvInventory)
    outputs.file(releaseSecurityEvidenceFile)
    outputs.upToDateWhen { false }

    doLast {
        val evidenceInputs = sortedMapOf(
            "release-dependency-inventory" to dependencyInventory.get().asFile,
            "release-license-evidence" to licenseEvidence.get().asFile,
            "release-osv-inventory" to releaseOsvInventory.asFile,
            "workspace-secret-scan" to workspaceSecretScanFile.get().asFile
        )
        val missing = evidenceInputs.filterValues { !it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Release security evidence inputs missing: ${missing.keys.joinToString()}")
        }
        val workspaceScanFields = workspaceSecretScanFile.get().asFile
            .readLines(Charsets.UTF_8)
            .filter(String::isNotBlank)
            .associate { line -> line.substringBefore('=') to line.substringAfter('=', "") }
        if (workspaceScanFields["history-status"] != "COMPLETE" ||
            workspaceScanFields["status"] != "PASS"
        ) {
            throw GradleException(
                "Release security evidence requires a complete passing reachable-history secret scan."
            )
        }
        val output = releaseSecurityEvidenceFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("schema=v-slot-release-security-evidence-v1")
                evidenceInputs.forEach { (name, file) ->
                    appendLine("$name-sha256=${sha256(file.readBytes())}")
                }
                appendLine("status=PASS")
            },
            Charsets.UTF_8
        )
    }
}

val releaseProvenanceFile = layout.buildDirectory.file("reports/release-provenance.txt")
val requiredReleaseJavaMajor = 17
val requiredReleaseGradleVersion = "8.14.5"
val requiredReleaseGradleDistributionSha256 =
    "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854"
val requiredReleaseBundletoolVersion = "1.18.3"
val requiredReleaseBundletoolSha256 =
    "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
extra["vSlotBundletoolVersion"] = requiredReleaseBundletoolVersion
extra["vSlotBundletoolSha256"] = requiredReleaseBundletoolSha256

val verifyReleaseProvenance = tasks.register("verifyReleaseProvenance") {
    group = "verification"
    description = "Verifies the production toolchain and clean committed source, then records release provenance."
    outputs.file(releaseProvenanceFile)
    outputs.upToDateWhen { false }

    doLast {
        fun safeProvenanceValue(name: String, rawValue: String?): String {
            val value = rawValue.orEmpty().trim()
            if (value.isBlank()) {
                throw GradleException("Release provenance value is missing: $name.")
            }
            if (!value.matches(Regex("[A-Za-z0-9][A-Za-z0-9 ._()+,@-]{0,127}"))) {
                throw GradleException("Release provenance value contains unsupported characters: $name.")
            }
            return value
        }

        fun javaMajor(specificationVersion: String): Int? {
            val components = specificationVersion.split('.')
            return if (components.firstOrNull() == "1") {
                components.getOrNull(1)?.toIntOrNull()
            } else {
                components.firstOrNull()?.toIntOrNull()
            }
        }

        val javaSpecificationVersion = safeProvenanceValue(
            "java.specification.version",
            System.getProperty("java.specification.version")
        )
        val actualJavaMajor = javaMajor(javaSpecificationVersion)
        if (actualJavaMajor != requiredReleaseJavaMajor) {
            throw GradleException(
                "Production release requires Java $requiredReleaseJavaMajor; " +
                    "running Java ${actualJavaMajor ?: "unknown"}."
            )
        }
        if (gradle.gradleVersion != requiredReleaseGradleVersion) {
            throw GradleException(
                "Production release requires Gradle Wrapper $requiredReleaseGradleVersion; " +
                    "running Gradle ${gradle.gradleVersion}."
            )
        }

        val wrapperPropertiesFile = rootProject.file("gradle/wrapper/gradle-wrapper.properties")
        if (!wrapperPropertiesFile.isFile) {
            throw GradleException("Production release requires the checked-in Gradle Wrapper properties.")
        }
        val wrapperProperties = Properties().apply {
            wrapperPropertiesFile.inputStream().use { input -> load(input) }
        }
        val expectedDistributionUrl =
            "https://services.gradle.org/distributions/gradle-$requiredReleaseGradleVersion-bin.zip"
        val wrapperDistributionUrl = wrapperProperties.getProperty("distributionUrl").orEmpty()
        val wrapperDistributionSha256 = wrapperProperties
            .getProperty("distributionSha256Sum")
            .orEmpty()
            .lowercase()
        if (wrapperDistributionUrl != expectedDistributionUrl ||
            wrapperDistributionSha256 != requiredReleaseGradleDistributionSha256 ||
            wrapperProperties.getProperty("validateDistributionUrl") != "true"
        ) {
            throw GradleException(
                "Production release requires the reviewed Gradle $requiredReleaseGradleVersion wrapper distribution."
            )
        }

        val androidSdkRoot = sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT")
        ).filterNotNull().firstOrNull(String::isNotBlank)
            ?.let(::File)
            ?: throw GradleException("Release provenance requires ANDROID_HOME or ANDROID_SDK_ROOT.")
        val androidPlatformJar = androidSdkRoot.resolve("platforms/android-36/android.jar")
        val androidAapt2 = androidSdkRoot.resolve("build-tools/36.0.0/aapt2")
        val androidDexdump = androidSdkRoot.resolve("build-tools/36.0.0/dexdump")
        if (!androidPlatformJar.isFile || !androidAapt2.isFile || !androidDexdump.isFile) {
            throw GradleException("Release provenance requires Android platform 36 and build-tools 36.0.0.")
        }
        val configuredAapt2Override = providers
            .gradleProperty("android.aapt2FromMavenOverride")
            .orNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: throw GradleException(
                "Production release requires android.aapt2FromMavenOverride to select the reviewed SDK aapt2."
            )
        if (configuredAapt2Override.canonicalFile != androidAapt2.canonicalFile) {
            throw GradleException(
                "Production release must run the reviewed Android build-tools 36.0.0 aapt2 binary."
            )
        }
        fun requiredToolchainSha256(name: String): String {
            val value = System.getenv(name).orEmpty().trim().lowercase()
            if (!value.matches(Regex("[0-9a-f]{64}")) || value.toSet().size == 1) {
                throw GradleException("Release provenance requires reviewed $name SHA-256.")
            }
            return value
        }
        val androidPlatformJarSha256 = sha256(androidPlatformJar.readBytes())
        val androidAapt2Sha256 = sha256(androidAapt2.readBytes())
        val androidDexdumpSha256 = sha256(androidDexdump.readBytes())
        val expectedAndroidPlatformJarSha256 = requiredToolchainSha256(
            "V_SLOT_ANDROID_PLATFORM_36_JAR_SHA256"
        )
        val expectedAndroidAapt2Sha256 = requiredToolchainSha256(
            "V_SLOT_ANDROID_BUILD_TOOLS_36_AAPT2_SHA256"
        )
        val expectedAndroidDexdumpSha256 = requiredToolchainSha256(
            "V_SLOT_ANDROID_BUILD_TOOLS_36_DEXDUMP_SHA256"
        )
        if (androidPlatformJarSha256 != expectedAndroidPlatformJarSha256) {
            throw GradleException("Android platform 36 android.jar does not match the reviewed SHA-256.")
        }
        if (androidAapt2Sha256 != expectedAndroidAapt2Sha256) {
            throw GradleException("Android build-tools 36.0.0 aapt2 does not match the reviewed SHA-256.")
        }
        if (androidDexdumpSha256 != expectedAndroidDexdumpSha256) {
            throw GradleException("Android build-tools 36.0.0 dexdump does not match the reviewed SHA-256.")
        }
        val bundletoolJar = System.getenv("V_SLOT_BUNDLETOOL_JAR")
            .orEmpty()
            .trim()
            .takeIf(String::isNotBlank)
            ?.let(::File)
            ?: throw GradleException("Release provenance requires V_SLOT_BUNDLETOOL_JAR.")
        if (!bundletoolJar.isFile) {
            throw GradleException("Release provenance bundletool JAR is missing: ${bundletoolJar.path}")
        }
        val bundletoolSha256 = sha256(bundletoolJar.readBytes())
        if (bundletoolSha256 != requiredReleaseBundletoolSha256) {
            throw GradleException(
                "Production release requires bundletool $requiredReleaseBundletoolVersion " +
                    "with the reviewed SHA-256."
            )
        }

        val environmentEvidence = linkedMapOf(
            "java-runtime-version" to safeProvenanceValue(
                "java.runtime.version",
                System.getProperty("java.runtime.version")
            ),
            "java-vendor" to safeProvenanceValue("java.vendor", System.getProperty("java.vendor")),
            "java-major" to actualJavaMajor.toString(),
            "gradle-version" to safeProvenanceValue("Gradle version", gradle.gradleVersion),
            "gradle-wrapper-distribution-sha256" to wrapperDistributionSha256,
            "android-platform-36-jar-sha256" to androidPlatformJarSha256,
            "android-build-tools-36.0.0-aapt2-sha256" to androidAapt2Sha256,
            "android-build-tools-36.0.0-dexdump-sha256" to androidDexdumpSha256,
            "android-aapt2-source" to "sdk-build-tools-36.0.0-override",
            "bundletool-version" to requiredReleaseBundletoolVersion,
            "bundletool-sha256" to bundletoolSha256,
            "os-name" to safeProvenanceValue("os.name", System.getProperty("os.name")),
            "os-version" to safeProvenanceValue("os.version", System.getProperty("os.version")),
            "os-arch" to safeProvenanceValue("os.arch", System.getProperty("os.arch"))
        )
        val githubRunnerEvidence = listOf(
            "github-image-os" to System.getenv("ImageOS"),
            "github-image-version" to System.getenv("ImageVersion")
        ).mapNotNull { (name, value) ->
            value?.takeIf(String::isNotBlank)?.let { name to safeProvenanceValue(name, it) }
        }

        fun gitOutput(vararg arguments: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return process.waitFor() to output
        }

        val (headExitCode, head) = gitOutput("rev-parse", "--verify", "HEAD")
        if (headExitCode != 0 || !head.matches(Regex("[0-9a-fA-F]{40,64}"))) {
            throw GradleException("Release provenance requires a committed Git HEAD.")
        }

        val (indexFlagsExitCode, indexFlags) = gitOutput("ls-files", "-v")
        if (indexFlagsExitCode != 0) {
            throw GradleException("Release provenance could not inspect Git index flags.")
        }
        val hiddenIndexPaths = indexFlags.lineSequence()
            .filter(String::isNotBlank)
            .filter { line -> line.first() != 'H' }
            .map { line -> line.drop(2) }
            .sorted()
            .toList()
        if (hiddenIndexPaths.isNotEmpty()) {
            throw GradleException(
                "Release provenance rejects assume-unchanged, skip-worktree, or other hidden index flags: " +
                    hiddenIndexPaths.joinToString()
            )
        }

        val (statusExitCode, status) = gitOutput(
            "status",
            "--porcelain=v1",
            "--untracked-files=all"
        )
        if (statusExitCode != 0) {
            throw GradleException("Release provenance could not inspect the Git worktree.")
        }
        if (status.isNotBlank()) {
            throw GradleException(
                "Release provenance requires a clean Git worktree; commit or remove all pending files first."
            )
        }

        val ignoredReleaseInputs = nullSeparatedGitPaths(
            "ls-files",
            "--others",
            "--ignored",
            "--exclude-standard",
            "-z",
            "--",
            "app/src/main",
            "app/src/release"
        ).filterNot { it == "app/src/release/google-services.json" }
        if (ignoredReleaseInputs.isNotEmpty()) {
            throw GradleException(
                "Release provenance rejects ignored source inputs: " +
                    ignoredReleaseInputs.sorted().joinToString()
            )
        }

        val (treeExitCode, tree) = gitOutput("rev-parse", "HEAD^{tree}")
        if (treeExitCode != 0 || !tree.matches(Regex("[0-9a-fA-F]{40,64}"))) {
            throw GradleException("Release provenance could not resolve the committed source tree.")
        }
        val googleServicesFile = rootProject.file("app/src/release/google-services.json")
        val googleServicesSha256 = googleServicesFile.takeIf(File::isFile)
            ?.let { file -> sha256(file.readBytes()) }
            ?: "MISSING"
        val releaseConfigurationDigest = MessageDigest.getInstance("SHA-256")
        listOf(
            "V_SLOT_PRIVACY_POLICY_URL",
            "V_SLOT_SUPPORT_EMAIL",
            "V_SLOT_DEVELOPER_LEGAL_NAME",
            "V_SLOT_APPMETRICA_API_KEY",
            "V_SLOT_APPMETRICA_API_KEY_SHA256",
            "V_SLOT_FIREBASE_PROJECT_ID",
            "V_SLOT_FIREBASE_APP_ID",
            "V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE",
            "V_SLOT_DATA_SAFETY_EVIDENCE_SHA256",
            "V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256",
            "V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE",
            "V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256",
            "V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256",
            "V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256",
            "V_SLOT_FRAME_METRICS_EVIDENCE_SHA256",
            "V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256",
            "V_SLOT_RELEASE_KEY_ALIAS",
            "V_SLOT_RELEASE_CERT_SHA256",
            "V_SLOT_ANDROID_PLATFORM_36_JAR_SHA256",
            "V_SLOT_ANDROID_BUILD_TOOLS_36_AAPT2_SHA256",
            "V_SLOT_ANDROID_BUILD_TOOLS_36_DEXDUMP_SHA256"
        ).forEach { name ->
            releaseConfigurationDigest.update(name.toByteArray(Charsets.UTF_8))
            releaseConfigurationDigest.update(0.toByte())
            releaseConfigurationDigest.update(System.getenv(name).orEmpty().toByteArray(Charsets.UTF_8))
            releaseConfigurationDigest.update('\n'.code.toByte())
        }
        val releaseConfigurationSha256 = releaseConfigurationDigest.digest()
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

        val output = releaseProvenanceFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("schema=v-slot-release-provenance-v6")
                appendLine("commit=${head.lowercase()}")
                appendLine("tree=${tree.lowercase()}")
                environmentEvidence.forEach { (name, value) -> appendLine("$name=$value") }
                githubRunnerEvidence.forEach { (name, value) -> appendLine("$name=$value") }
                appendLine("release-google-services-sha256=$googleServicesSha256")
                appendLine("release-configuration-sha256=$releaseConfigurationSha256")
                appendLine("status=PASS")
            },
            Charsets.UTF_8
        )
    }
}
