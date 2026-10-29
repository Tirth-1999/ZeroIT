package zeroit.protocol

import java.nio.file.Files
import java.nio.file.Path

/** Verifies structured projections rather than only successful generic decoding. */
fun dtoAssertions(decoder: WireDecoder, contract: Path): Int {
    var count = 0
    fun verify(ok: Boolean) { check(ok) { "DTO/immutable tree assertion failed" }; count++ }
    fun decode(definition: String, file: String) = decoder.decode(definition,Files.readAllBytes(contract.resolve("fixtures/$file.json")))
    val expense = decode("command","expense-create") as CommandDto
    val payload = expense.payload as CommandPayload.ExpenseCreate
    verify(payload.amount.decimal == "6000" && payload.owed.map { it.amount.decimal } == listOf("3000","3000"))
    verify(expense.preconditions.membershipGeneration?.text == "00000000-0000-4000-8000-000000000012")
    verify((decode("command","group-create") as CommandDto).preconditions.epoch == null)
    val rejoin = (decode("command","invite-rejoin") as CommandDto).payload as CommandPayload.InviteAccept
    verify(rejoin.mode == "rejoin" && rejoin.newParticipant == null && rejoin.displayName == null)
    verify((decode("receipt","accepted") as ReceiptDto).groupRevision?.decimal == "4")
    val revoked = decode("receipt","revoked-receipt") as ReceiptDto
    verify(revoked.status == "accepted_but_access_revoked" && revoked.records.isEmpty() && revoked.groupRevision == null)
    verify((decode("batch_response","batch-rate-limited") as ResultsDto).results.single().retryAfterSeconds == 5)
    verify((decode("feed","story-feed") as FeedDto).events.map { it.revision.decimal } == listOf("1","2","3","4"))
    verify((decode("snapshot_manifest","story-snapshot-manifest") as ManifestDto).changesCursor.isNotBlank())
    verify((decode("snapshot_page","story-snapshot-page") as PageDto).records.count { it.kind == "membership" } == 2)
    verify((decode("discovery","discovery") as DiscoveryDto).changes.single().role == "owner")
    verify((decode("bootstrap_response","bootstrap-response") as BootstrapDto).account != null)
    verify((decode("invite_resolution","invite-resolution") as InviteRoutingDto).inviteVersion?.decimal == "1")
    val tokenMessage = decode("command","invite-issue")
    verify(!tokenMessage.toString().contains("A".repeat(43)))
    val source = linkedMapOf<String,JValue>("operation" to JString("immutable"))
    val frozen = JObject(source); source["operation"] = JString("changed")
    verify(frozen.fields.getValue("operation").str() == "immutable")
    try { (frozen.fields as MutableMap)["operation"] = JString("mutated"); error("Tree map mutable") }
    catch (_: UnsupportedOperationException) { count++ }
    val arraySource = mutableListOf<JValue>(JString("immutable")); val frozenArray = JArray(arraySource); arraySource.clear()
    verify(frozenArray.values.single().str() == "immutable")
    try { (frozenArray.values as MutableList).clear(); error("Tree array mutable") }
    catch (_: UnsupportedOperationException) { count++ }
    return count
}
