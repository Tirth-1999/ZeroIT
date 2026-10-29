package zeroit.protocol

import java.math.BigInteger
import java.time.Duration
import java.time.Instant

/** Pure checks after structural decoding; this is not authorization or a command executor. */
object Semantics {
    private val bigintLimit = BigInteger("9223372036854775807")
    private val revisionFields = setOf(
        "version", "revision", "after_revision", "high_water_revision", "membership_revision",
        "membership_version", "expected_entity_version", "expected_membership_revision",
        "group_revision", "entity_version",
    )
    private val currencyScales = mapOf("USD" to 2, "JPY" to 0, "KWD" to 3)

    /** Throws IllegalArgumentException on a selected semantic or canonical-profile violation. */
    fun validate(definition: String, value: JValue) {
        checkRevisions(value)
        when (definition) {
            "command_batch" -> {
                val commands = value.field("commands").arr()
                val ids = commands.map { it.field("operation_id").str() }
                require(ids.distinct().size == ids.size) { "duplicate batch operation" }
                commands.forEach { validate("command", it) }
            }
            "command" -> validateCommand(value)
            "feed", "discovery" -> validateFeed(definition, value)
            "snapshot_manifest" -> validateSnapshotManifest(value)
        }
        val bytes = Canonical.bytes(value)
        when (definition) {
            "command" -> require(bytes.size <= 250 * 1024) { "command byte bound" }
            "command_batch" -> require(bytes.size <= 1024 * 1024) { "batch byte bound" }
        }
    }

    private fun checkRevisions(value: JValue) {
        when (value) {
            is JObject -> value.fields.forEach { (name, item) ->
                if (name in revisionFields && item != JNull) {
                    require(BigInteger(item.str()) <= bigintLimit) { "signed bigint overflow" }
                }
                checkRevisions(item)
            }
            is JArray -> value.values.forEach(::checkRevisions)
            else -> Unit
        }
    }

    private fun validateCommand(command: JValue) {
        val operationId = command.field("operation_id").str()
        require(command.field("dependencies").arr().none { it.str() == operationId }) { "self dependency" }
        when (command.field("action").str()) {
            "group.create" -> require(command.field("entity_id") == command.field("group_id")) {
                "group create ID mismatch"
            }
            "expense.create" -> validateExpense(command.field("payload"))
        }
    }

    private fun validateExpense(payload: JValue) {
        val total = BigInteger(payload.field("amount_minor").str())
        val order = payload.field("participant_order").arr().map { it.str() }
        val currency = payload.field("currency").str()
        require(currencyScales[currency] == payload.field("currency_scale").int()) {
            "currency catalog/scale mismatch"
        }
        require(order.isNotEmpty()) { "empty participant order" }
        for (name in listOf("paid", "owed")) {
            val rows = payload.field(name).arr()
            val ids = rows.map { it.field("participant_id").str() }
            require(ids.distinct().size == ids.size) { "duplicate allocation participant" }
            require(ids.all { it in order }) { "allocation outside declared participants" }
            require(ids == order.filter { it in ids }) { "allocation order mismatch" }
            val sum = rows.fold(BigInteger.ZERO) { accumulated, row ->
                accumulated + BigInteger(row.field("amount_minor").str())
            }
            require(sum == total) { "allocation sum mismatch" }
        }
        val (quotient, remainder) = total.divideAndRemainder(BigInteger.valueOf(order.size.toLong()))
        val expected = order.mapIndexed { index, participant ->
            val amount = quotient + if (BigInteger.valueOf(index.toLong()) < remainder) BigInteger.ONE else BigInteger.ZERO
            JObject(mapOf("participant_id" to JString(participant), "amount_minor" to JString(amount.toString())))
        }
        require(payload.field("owed").arr() == expected) { "equal split mismatch" }
    }

    private fun validateFeed(definition: String, value: JValue) {
        val rows = value.field(if (definition == "feed") "events" else "changes").arr()
        val start = BigInteger(value.field("after_revision").str())
        val high = BigInteger(value.field("high_water_revision").str())
        require(high >= start) { "high water before cursor" }
        rows.forEachIndexed { index, row ->
            val expected = start + BigInteger.valueOf(index.toLong() + 1)
            require(BigInteger(row.field("revision").str()) == expected) { "feed gap/order mismatch" }
        }
        val last = rows.lastOrNull()?.field("revision")?.str()?.let(::BigInteger) ?: start
        require(last <= high) { "beyond high water" }
        val hasMore = value.field("has_more").bool()
        require(hasMore == (last < high)) { "has_more mismatch" }
        require(rows.isNotEmpty() || !hasMore) { "empty page cannot stall cursor" }
        if (definition == "discovery") {
            rows.forEach { row ->
                require((row.field("role") != JNull) == (row.field("status").str() == "active")) {
                    "removed discovery role disclosure"
                }
            }
        }
    }

    private fun validateSnapshotManifest(value: JValue) {
        val fields = value.obj()
        require(value.field("page_count").int() == value.field("page_hashes").arr().size) { "page count mismatch" }
        val duration = try {
            Duration.between(Instant.parse(value.field("created_at").str()), Instant.parse(value.field("expires_at").str()))
        } catch (failure: java.time.DateTimeException) {
            throw IllegalArgumentException("snapshot timestamp invalid", failure)
        }
        require(!duration.isZero && !duration.isNegative && duration <= Duration.ofMinutes(30)) { "snapshot lease bound" }
        val hashed = JObject(fields.filterKeys { it != "manifest_hash" && it != "snapshot_token" })
        require(Canonical.sha256(hashed) == value.field("manifest_hash").str()) { "manifest hash mismatch" }
    }

    private fun JValue.field(name: String): JValue = obj()[name]
        ?: throw IllegalArgumentException("missing field: $name")
}
