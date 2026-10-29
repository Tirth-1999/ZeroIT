package zeroit.protocol

/** Wire text is retained exactly: no UUID rewriting, trimming or decimal normalization. */
@JvmInline value class WireId(val text: String)
@JvmInline value class Revision(val decimal: String)
@JvmInline value class MinorUnits(val decimal: String)
data class Preconditions(val epoch: WireId?, val entityVersion: Revision, val membershipRevision: Revision?, val membershipGeneration: WireId?)
data class Allocation(val participant: WireId, val amount: MinorUnits)
sealed interface CommandPayload {
    data class GroupCreate(val name: String, val currency: String, val ownerParticipant: WireId, val ownerName: String) : CommandPayload
    data class InviteIssue(val token: String, val role: String) : CommandPayload { override fun toString() = "InviteIssue(<redacted>)" }
    data class InviteAccept(val token: String, val mode: String, val newParticipant: WireId?, val displayName: String?) : CommandPayload { override fun toString() = "InviteAccept(<redacted>)" }
    data object InviteRevoke : CommandPayload
    data class ExpenseCreate(val title: String, val notes: String, val currency: String, val scale: Int, val amount: MinorUnits, val occurredAt: String, val participantOrder: List<WireId>, val paid: List<Allocation>, val owed: List<Allocation>) : CommandPayload
}
sealed interface WireMessage { val definition: String; val tree: JValue }
data class CommandDto(val operation: WireId, val group: WireId, val entity: WireId, val action: String, val dependencies: List<WireId>, val preconditions: Preconditions, val payload: CommandPayload, override val tree: JValue) : WireMessage { override val definition = "command"; override fun toString() = "CommandDto(action=$action, payload=<redacted>)" }
data class BatchDto(val commands: List<CommandDto>, override val tree: JValue) : WireMessage { override val definition = "command_batch"; override fun toString() = "BatchDto(data=<redacted>)" }
/** Non-command closed variants retain their discriminated schema definition and immutable tree. */
data class DocumentDto(override val definition: String, override val tree: JValue) : WireMessage { override fun toString() = "DocumentDto(definition=$definition, data=<redacted>)" }

class WireDecoder(private val schema: JValue) {
    private val validator = SchemaValidator(schema)
    fun decode(definition: String, bytes: ByteArray): WireMessage {
        val tree = StrictJson.parse(bytes)
        validator.validate(definition,tree)
        Semantics.validate(definition,tree)
        return when (definition) {
            "command" -> command(tree)
            "command_batch" -> BatchDto(tree.obj().getValue("commands").arr().map(::command),tree)
            else -> ResponseDecoder.decode(definition,tree)
        }
    }
    private fun command(tree: JValue): CommandDto {
        val f=tree.obj(); val p=f.getValue("payload").obj(); val action=f.getValue("action").str()
        fun nullableId(k: String) = f.getValue(k).takeUnless { it == JNull }?.let { WireId(it.str()) }
        fun allocations(k: String) = p.getValue(k).arr().map { a -> Allocation(WireId(a.obj().getValue("participant_id").str()), MinorUnits(a.obj().getValue("amount_minor").str())) }
        val payload = when (action) {
            "group.create" -> CommandPayload.GroupCreate(p.getValue("name").str(),p.getValue("default_currency").str(),WireId(p.getValue("owner_participant_id").str()),p.getValue("owner_display_name").str())
            "invite.issue" -> CommandPayload.InviteIssue(p.getValue("token").str(),p.getValue("role").str())
            "invite.accept" -> CommandPayload.InviteAccept(p.getValue("token").str(),p.getValue("join_mode").str(),p["new_participant_id"]?.let { WireId(it.str()) },p["display_name"]?.str())
            "invite.revoke" -> CommandPayload.InviteRevoke
            "expense.create" -> CommandPayload.ExpenseCreate(p.getValue("title").str(),p.getValue("notes").str(),p.getValue("currency").str(),p.getValue("currency_scale").int(),MinorUnits(p.getValue("amount_minor").str()),p.getValue("occurred_at").str(),p.getValue("participant_order").arr().map { WireId(it.str()) },allocations("paid"),allocations("owed"))
            else -> throw IllegalArgumentException("Unsupported command")
        }
        return CommandDto(WireId(f.getValue("operation_id").str()),WireId(f.getValue("group_id").str()),WireId(f.getValue("entity_id").str()),action,f.getValue("dependencies").arr().map { WireId(it.str()) },Preconditions(nullableId("expected_feed_epoch"),Revision(f.getValue("expected_entity_version").str()),f.getValue("expected_membership_revision").takeUnless { it == JNull }?.let { Revision(it.str()) },nullableId("expected_membership_generation")),payload,tree)
    }
}
