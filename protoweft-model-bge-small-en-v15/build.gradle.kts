import java.security.MessageDigest
import java.util.zip.ZipFile

// The BAAI bge-small-en-v1.5 model (quantized ONNX) and its WordPiece vocabulary as classpath
// resources, for protoweft-discovery-embeddings. No code. Nothing binary is kept in git: the
// model and vocabulary are extracted from a Maven Central artifact and checked by SHA-256.
// See protoweft-discovery-embeddings/SPEC.md.

description = "BAAI bge-small-en-v1.5 (quantized ONNX) and its vocabulary, for protoweft-discovery-embeddings"

val resourceDir = "io/github/rahulbsw/protoweft/models/bge-small-en-v15"
val sourceArtifact =
    "dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:${project.property("langchain4jVersion")}-beta33"
val modelEntry = "bge-small-en-v1.5-q.onnx"
val tokenizerEntry = "bge-small-en-v1.5-q-tokenizer.json"
val modelSha256 = "6c9c6101a956d62dfb5e7190c538226c0c5bb9cb27b651234b6df063ee7dbfe4"
val tokenizerSha256 = "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66"
val vocabSha256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"
val vocabSize = 30522

// Resolved on its own and non-transitively: only the jar's files are used, its code and
// dependencies never reach any classpath.
val modelSource: Configuration = configurations.create("modelSource") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    modelSource(sourceArtifact)
}

val extractModel = tasks.register("extractModel") {
    description = "Extracts model.onnx and vocab.txt from $sourceArtifact and verifies their SHA-256."
    val sourceFiles: FileCollection = modelSource
    val outputDir = layout.buildDirectory.dir("generated/model")
    inputs.files(sourceFiles)
    inputs.property("sha256", listOf(modelSha256, tokenizerSha256, vocabSha256))
    outputs.dir(outputDir)
    doLast {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun check(name: String, bytes: ByteArray, expected: String) {
            val actual = sha256(bytes)
            if (actual != expected) {
                throw GradleException("$name: SHA-256 $actual, expected $expected")
            }
        }

        val zip = ZipFile(sourceFiles.singleFile)
        fun read(name: String): ByteArray {
            val entry = zip.getEntry(name) ?: throw GradleException("$name not found in $sourceArtifact")
            val input = zip.getInputStream(entry)
            try {
                return input.readBytes()
            } finally {
                input.close()
            }
        }
        val model: ByteArray
        val tokenizer: ByteArray
        try {
            model = read(modelEntry)
            tokenizer = read(tokenizerEntry)
        } finally {
            zip.close()
        }
        check(modelEntry, model, modelSha256)
        check(tokenizerEntry, tokenizer, tokenizerSha256)

        // vocab.txt: one token per line, the line number is the token id (BERT's format).
        @Suppress("UNCHECKED_CAST")
        val vocab = ((groovy.json.JsonSlurper().parse(tokenizer) as Map<String, Any>)["model"]
            as Map<String, Any>)["vocab"] as Map<String, Number>
        val byId = arrayOfNulls<String>(vocab.size)
        vocab.forEach { (token, id) ->
            val i = id.toInt()
            if (i !in byId.indices || byId[i] != null || token.contains('\n') || token.contains('\r')) {
                throw GradleException("Unexpected vocabulary entry $i: '$token'")
            }
            byId[i] = token
        }
        if (byId.size != vocabSize) {
            throw GradleException("Vocabulary has ${byId.size} entries, expected $vocabSize")
        }
        val vocabBytes = byId.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
        check("vocab.txt", vocabBytes, vocabSha256)

        val dir = outputDir.get().dir(resourceDir).asFile
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("model.onnx").writeBytes(model)
        dir.resolve("vocab.txt").writeBytes(vocabBytes)
    }
}

// Added to the jar only (not as a source directory), so the sources jar stays small.
tasks.named<ProcessResources>("processResources") {
    from(extractModel)
}
