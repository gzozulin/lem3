import java.io.File
import java.util.concurrent.TimeUnit

private val PATTERN_WHITESPACE = "\\s".toRegex()
private val PATTERN_NEW_LINE = "\\n".toRegex()

private enum class CommitStage { COMMIT, AUTHOR, DATE, MESSAGE, DIFF }
private enum class DiffStage { HEADER, MODE, INDEX, PREIMAGE, POSTIMAGE, HUNKS }
private enum class HunkStages { RANGE, LINES }

private enum class ModifType { ADD, REMOVE, SAME }

private data class ShortCommit(val shortHash: String, val shortMsg: String)
private data class FullCommit(val hash: String, val author: String, val date: String, val message: String,
                              val parsedDiffs: List<Diff>)
private data class Diff(val header: String, val mode: String?, val index: String?, val preimage: String,
                        val postimage: String, val parsedHunks: List<Hunk>)
private data class Hunk(val range: String, val parsedLines: List<Line>)
private data class Line(val modifType: ModifType, val payload: String)

fun main() {
    val commits = parseCommits(listCommits("ecda81e", "1c5a7e3"))
    val fullCommit = parseCommit(showCommit("1c5a7e3"))
    TODO()
}

fun String.runCommand(workingDir: File): String {
    val parts = this.split(PATTERN_WHITESPACE)
    val proc = ProcessBuilder(*parts.toTypedArray())
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    proc.waitFor(60, TimeUnit.MINUTES)
    return proc.inputStream.bufferedReader().readText()
}

private fun listCommits(from: String, to: String) =
    "git --no-pager log $from $to --oneline".runCommand(File("./"))

private fun showCommit(hash: String) =
    "git --no-pager show $hash".runCommand(File("./"))

private fun parseCommits(list: String) =
    list.split(PATTERN_NEW_LINE)
        .map {
            val firstSpace = it.indexOfFirst { it == ' ' }
            val hash = it.substring(0 until firstSpace)
            val shortMessage = it.substring(firstSpace+1 until it.length)
            ShortCommit(hash, shortMessage)
        }

private fun parseCommit(commit: String): FullCommit {
    var stage = CommitStage.COMMIT
    lateinit var hash: String
    lateinit var author: String
    lateinit var date: String
    var message = ""
    val diffs = mutableListOf<String>()
        commit.split(PATTERN_NEW_LINE).forEach {
        when (stage) {
            CommitStage.COMMIT -> {
                hash = it
                stage = CommitStage.AUTHOR
            }
            CommitStage.AUTHOR -> {
                author = it
                stage = CommitStage.DATE
            }
            CommitStage.DATE -> {
                date = it
                stage = CommitStage.MESSAGE
            }
            CommitStage.MESSAGE -> {
                if (it.startsWith("diff")) {
                    diffs.add(it)
                    stage = CommitStage.DIFF
                } else {
                    message += (it + "\n")
                }
            }
            CommitStage.DIFF -> {
                if (it.startsWith("diff")) {
                    diffs.add(it)
                } else {
                    diffs.add(diffs.removeLast() + it + "\n")
                }
            }
        }
    }
    val parsedDiffs = diffs.map { parseDiff(it) }
    return FullCommit(hash, author, date, message, parsedDiffs)
}

private fun parseDiff(diff: String): Diff {
    var stage = DiffStage.HEADER
    lateinit var header: String
    var mode: String? = null
    var index: String? = null
    lateinit var preimage: String
    lateinit var postimage: String
    val hunks = mutableListOf<String>()
    diff.split(PATTERN_NEW_LINE).forEach {
        when (stage) {
            DiffStage.HEADER -> {
                header = it
                stage = DiffStage.MODE
            }
            DiffStage.MODE -> {
                when {
                    it.startsWith("new file mode") -> {
                        mode = it
                        stage = DiffStage.INDEX
                    }
                    it.startsWith("index") -> {
                        index = it
                        stage = DiffStage.PREIMAGE
                    }
                    it.startsWith("--") -> {
                        preimage = it
                        stage = DiffStage.POSTIMAGE
                    }
                    else -> error("wtf?!")
                }
            }
            DiffStage.INDEX -> {
                index = it
                stage = DiffStage.PREIMAGE
            }
            DiffStage.PREIMAGE -> {
                preimage = it
                stage = DiffStage.POSTIMAGE
            }
            DiffStage.POSTIMAGE -> {
                postimage = it
                stage = DiffStage.HUNKS
            }
            DiffStage.HUNKS -> {
                if (it.startsWith("@@")) {
                    hunks.add(it)
                } else {
                    hunks.add(hunks.removeLast() + it + "\n")
                }
            }
        }
    }
    val parsedHunks = hunks.map { parseHunk(it) }
    return Diff(header, mode, index, preimage, postimage, parsedHunks)
}

private fun parseHunk(hunk: String): Hunk {
    var stage = HunkStages.RANGE
    lateinit var range: String
    val lines = mutableListOf<String>()
    hunk.split(PATTERN_NEW_LINE).forEach {
        when (stage) {
            HunkStages.RANGE -> {
                range = it
                stage = HunkStages.LINES
            }
            HunkStages.LINES -> {
                if (!it.contains("No newline")) {
                    lines.add(it)
                }
            }
        }
    }
    val parsedLines = lines.filter { it.isNotEmpty() }.map { parseLine(it) }
    return Hunk(range, parsedLines)
}

private fun parseLine(line: String): Line {
    val modifType = when (line[0]) {
        '+' -> ModifType.ADD
        '-' -> ModifType.REMOVE
        ' ' -> ModifType.SAME
        else -> error("wtf?!")
    }
    val payload = line.substring(1 until line.length)
    return Line(modifType, payload)
}