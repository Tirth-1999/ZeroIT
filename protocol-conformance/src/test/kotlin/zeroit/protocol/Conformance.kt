package zeroit.protocol

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Pure host conformance entry point. Golden files are read-only, never regenerated here. */
fun main(args: Array<String>) {
    val project = Path.of(args.getOrElse(0) { "." }).toAbsolutePath().normalize()
    val contract = project.resolve("golden")
    fun load(path: Path): JValue = StrictJson.parse(Files.readAllBytes(path))
    fun fixture(name: String): JValue = load(contract.resolve("fixtures/$name.json"))
    fun rawSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun assertThat(condition: Boolean, label: String) {
        check(condition) { label }
    }
    fun rejects(label: String, action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        error("Expected rejection: $label")
    }
    fun schemaAccepted(action: () -> Unit): Boolean = try {
        action(); true
    } catch (_: IllegalArgumentException) {
        false
    }
    val acceptance = load(contract.resolve("manifest.json")).obj()
    assertThat(acceptance.getValue("revision").str() == "zeroit-local-conformance-v1", "unsupported golden revision")
    val digests = acceptance.getValue("artifacts_sha256").obj()
    assertThat(digests.size == 80, "golden bundle must retain exact 80-file scope")
    fun verifyAcceptedDigests() {
        val actualFiles = Files.walk(contract).use { paths ->
            paths.filter { Files.isRegularFile(it) && it != contract.resolve("manifest.json") }
                .map { contract.relativize(it).toString().replace(java.io.File.separatorChar, '/') }.toList().toSet()
        }
        assertThat(actualFiles == digests.keys, "unmanifested or missing golden file")
        digests.forEach { (relative, expected) ->
            val path = contract.resolve(relative).normalize()
            assertThat(path.startsWith(contract), "golden path escaped bundle")
            assertThat(rawSha(Files.readAllBytes(path)) == expected.str(), "accepted artifact changed: $relative")
        }
    }
    verifyAcceptedDigests()

    val schema = load(contract.resolve("schema.json"))
    val validator = SchemaValidator(schema)
    val decoder = WireDecoder(schema)
    val manifest = load(contract.resolve("fixtures.json")).arr()
    assertThat(manifest.size == 77, "golden case count changed")
    val expectedFixtureNames = manifest.map { it.obj().getValue("file").str() }.toSet()
    val actualFixtureNames = Files.list(contract.resolve("fixtures")).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".json") }
            .map { "fixtures/" + it.fileName.toString() }.toList().toSet()
    }
    assertThat(expectedFixtureNames == actualFixtureNames, "unmanifested/missing fixture")
    var schemaValid = 0
    var schemaInvalid = 0
    var semanticInvalid = 0
    var decodedMessages = 0
    manifest.forEach { caseValue ->
        val case = caseValue.obj()
        val relative = case.getValue("file").str()
        val definition = case.getValue("definition").str()
        val fixturePath = contract.resolve(relative).normalize()
        assertThat(fixturePath.startsWith(contract), "fixture path escaped contract")
        val bytes = Files.readAllBytes(fixturePath)
        val value = StrictJson.parse(bytes)
        val shapeValid = schemaAccepted { validator.validate(definition, value) }
        assertThat(shapeValid == case.getValue("schema_valid").bool(), "$relative: unexpected schema outcome")
        if (shapeValid) {
            schemaValid++
            val meaningValid = schemaAccepted { Semantics.validate(definition, value) }
            assertThat(meaningValid == case.getValue("semantic_valid").bool(), "$relative: unexpected semantic outcome")
            if (meaningValid) {
                decoder.decode(definition, bytes)
                decodedMessages++
            } else {
                semanticInvalid++
                rejects("decoder must preserve semantic rejection for $relative") { decoder.decode(definition, bytes) }
            }
        } else {
            schemaInvalid++
            rejects("decoder must preserve schema rejection for $relative") { decoder.decode(definition, bytes) }
        }
    }
    assertThat(schemaValid == 48 && schemaInvalid == 29 && semanticInvalid == 12 && decodedMessages == 36, "golden result counts differ")

    val vector = load(contract.resolve("hash-vector.json")).obj()
    val command = load(contract.resolve(vector.getValue("fixture").str()))
    assertThat(Canonical.bytes(command).contentEquals(vector.getValue("canonical_utf8").str().toByteArray(UTF_8)), "exact canonical UTF-8 vector")
    assertThat(Canonical.sha256(command) == vector.getValue("sha256").str(), "exact command SHA-256 vector")
    assertThat(fixture("accepted").obj().getValue("request_hash").str() == Canonical.sha256(command), "receipt request hash")
    val page = fixture("snapshot-page")
    val snapshotManifest = fixture("snapshot-manifest").obj()
    assertThat(snapshotManifest.getValue("page_hashes").arr()[page.obj().getValue("page_index").int()].str() == Canonical.sha256(page), "snapshot page hash")
    val story = fixture("story-snapshot-page")
    val storyManifest = fixture("story-snapshot-manifest").obj()
    assertThat(storyManifest.getValue("page_hashes").arr()[0].str() == Canonical.sha256(story), "connected story page hash")

    // Five connected-story checks: sole owner, linkage, numeric oracle and feed equivalence.
    val records = story.obj().getValue("records").arr()
    val members = records.filter { it.obj().getValue("type").str() == "membership" }
    val participants = records.filter { it.obj().getValue("type").str() == "participant" }
        .associateBy { it.obj().getValue("id").str() }
    assertThat(members.count { it.obj().getValue("role").str() == "owner" && it.obj().getValue("status").str() == "active" } == 1, "sole connected story owner")
    members.forEach { member ->
        val fields = member.obj()
        assertThat(participants.getValue(fields.getValue("participant_id").str()).obj().getValue("account_id") == fields.getValue("account_id"), "story account-participant linkage")
    }
    val balances = participants.keys.associateWith { BigInteger.ZERO }.toMutableMap()
    records.filter { it.obj().getValue("type").str() == "expense" }.forEach { record ->
        val expense = record.obj().getValue("value").obj()
        expense.getValue("paid").arr().forEach { allocation ->
            val fields = allocation.obj()
            val id = fields.getValue("participant_id").str()
            balances[id] = balances.getValue(id) + fields.getValue("amount_minor").str().toBigInteger()
        }
        expense.getValue("owed").arr().forEach { allocation ->
            val fields = allocation.obj()
            val id = fields.getValue("participant_id").str()
            balances[id] = balances.getValue(id) - fields.getValue("amount_minor").str().toBigInteger()
        }
    }
    assertThat(balances.values.sorted() == listOf(BigInteger.valueOf(-3000), BigInteger.valueOf(3000)) && balances.values.fold(BigInteger.ZERO, BigInteger::add) == BigInteger.ZERO, "independent connected story USD oracle")
    fun recordKey(record: JValue): Pair<String, String> {
        val fields = record.obj()
        return fields.getValue("type").str() to (fields["id"] ?: fields.getValue("account_id")).str()
    }
    val reconstructed = linkedMapOf<Pair<String, String>, JValue>()
    fixture("story-feed").obj().getValue("events").arr().forEach { event ->
        event.obj().getValue("records").arr().forEach { record -> reconstructed[recordKey(record)] = record }
    }
    assertThat(reconstructed == records.associateBy(::recordKey), "connected feed/snapshot canonical record equivalence")

    val duplicateCases = listOf(
        """{"operation_id":"a","operation_id":"b"}""",
        """{"payload":{"amount_minor":"1","amount_minor":"2"}}"""
    )
    duplicateCases.forEach { raw -> rejects("duplicate JSON key") { StrictJson.parse(raw) } }
    // Original six accepted-profile assertions, independent of golden fixture outcomes.
    rejects("floating point canonical AST") { Canonical.bytes(JNumber("1.1")) }
    rejects("lone surrogate canonical AST") { Canonical.bytes(JString("\uD800")) }
    rejects("non-ASCII canonical property") { Canonical.bytes(JObject(mapOf("nonascii☃" to JNumber("1")))) }
    assertThat(Canonical.bytes(JObject(mapOf("title" to JString("Snow ☃"), "version" to JString("1")))).contentEquals("""{"title":"Snow ☃","version":"1"}""".toByteArray(UTF_8)), "non-ASCII string remains UTF-8")
    val reordered = JObject(command.obj().entries.toList().asReversed().associate { it.toPair() })
    assertThat(Canonical.sha256(reordered) == Canonical.sha256(command), "object key order invariant")
    val operationChanged = JObject(command.obj() + ("operation_id" to JString("00000000-0000-4000-8000-000000000099")))
    assertThat(Canonical.sha256(operationChanged) != Canonical.sha256(command), "operation ID changes hash")

    val parseRejections = listOf(
        """{"a":1,"\u0061":2}""".toByteArray(UTF_8),
        byteArrayOf(0xC3.toByte(), 0x28),
        "\"\\ud800\"".toByteArray(UTF_8),
        "\"\\udc00\"".toByteArray(UTF_8),
        "\"\\ud800x\"".toByteArray(UTF_8),
        "1.1".toByteArray(UTF_8),
        "1e2".toByteArray(UTF_8),
        "9007199254740992".toByteArray(UTF_8),
        "01".toByteArray(UTF_8),
        "null true".toByteArray(UTF_8),
        "\"bad\ncontrol\"".toByteArray(UTF_8)
    )
    parseRejections.forEachIndexed { i, bytes -> rejects("strict parse rejection $i") { StrictJson.parse(bytes) } }
    rejects("unsafe integer canonical AST") { Canonical.bytes(JNumber("9007199254740992")) }
    assertThat(Canonical.sha256(JArray(listOf(JString("A"), JString("B")))) != Canonical.sha256(JArray(listOf(JString("B"), JString("A")))), "array order changes hash")
    assertThat(Canonical.sha256(JString("é")) != Canonical.sha256(JString("e\u0301")), "no Unicode normalization")
    assertThat(Canonical.bytes(StrictJson.parse("""{"title":"\ud83d\ude00"}""")).contentEquals("""{"title":"😀"}""".toByteArray(UTF_8)), "paired surrogate encodes scalar UTF-8")
    assertThat(Canonical.bytes(StrictJson.parse("""{"text":"\b\t\n\f\r\u0000\"\\/"}""")).contentEquals("""{"text":"\b\t\n\f\r\u0000\"\\/"}""".toByteArray(UTF_8)), "minimal JSON escapes")

    val dtoChecks = dtoAssertions(decoder, contract)
    verifyAcceptedDigests()
    val report = """{
  "status": "passed",
  "schema_definitions": ${schema.obj().getValue("\$defs").obj().size},
  "fixtures": ${manifest.size},
  "schema_valid": $schemaValid,
  "schema_invalid": $schemaInvalid,
  "semantic_invalid": $semanticInvalid,
  "decoded_messages": $decodedMessages,
  "golden_artifact_digests_before_and_after": ${digests.size},
  "cross_fixture_hash_checks": 4,
  "connected_story_checks": 5,
  "duplicate_key_checks": 2,
  "canonical_profile_checks": 6,
  "additional_strict_parse_checks": ${parseRejections.size},
  "additional_canonical_checks": 5,
  "dto_and_immutability_checks": $dtoChecks,
  "runtime_or_database_checks": 0
}"""
    println(report)
    if (args.size > 1) {
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        assertThat(!output.startsWith(contract), "report cannot overwrite golden bundle")
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, report + "\n", UTF_8)
    }
}
