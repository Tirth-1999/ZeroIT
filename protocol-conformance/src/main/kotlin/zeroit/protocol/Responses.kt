package zeroit.protocol

/** Closed-schema DTO projections; optional fields model the accepted discriminated variants. */
data class ProtocolError(val code: String, val retryable: Boolean)
data class RecordDto(val kind: String, val id: WireId?, val account: WireId?, val version: Revision, val membershipGeneration: WireId?, val tree: JValue) {
    override fun toString() = "RecordDto(kind=$kind, data=<redacted>)"
}
data class ReceiptDto(val operation: WireId, val status: String, val requestHash: String?, val receiptId: WireId?, val replayed: Boolean?, val entityVersion: Revision?, val groupRevision: Revision?, val epoch: WireId?, val records: List<RecordDto>, val error: ProtocolError?, override val definition: String, override val tree: JValue) : WireMessage { override fun toString() = "ReceiptDto(status=$status, data=<redacted>)" }
data class ResultDto(val operation: WireId, val receipt: ReceiptDto?, val error: ProtocolError?, val retryAfterSeconds: Int?)
data class ResultsDto(val results: List<ResultDto>, override val tree: JValue) : WireMessage { override val definition = "batch_response"; override fun toString() = "ResultsDto(data=<redacted>)" }
data class EventDto(val revision: Revision, val operation: WireId, val actor: WireId, val committedAt: String, val action: String, val records: List<RecordDto>)
data class FeedDto(val group: WireId, val generation: WireId, val epoch: WireId, val after: Revision, val highWater: Revision, val events: List<EventDto>, val hasMore: Boolean, val nextCursor: String, override val tree: JValue) : WireMessage { override val definition = "feed"; override fun toString() = "FeedDto(data=<redacted>)" }
data class DiscoveryChange(val revision: Revision, val group: WireId, val generation: WireId, val status: String, val membershipVersion: Revision, val role: String?)
data class DiscoveryDto(val account: WireId, val epoch: WireId, val after: Revision, val highWater: Revision, val changes: List<DiscoveryChange>, val hasMore: Boolean, val nextCursor: String, override val tree: JValue) : WireMessage { override val definition = "discovery"; override fun toString() = "DiscoveryDto(data=<redacted>)" }
data class ManifestDto(val snapshot: WireId, val group: WireId, val account: WireId, val generation: WireId, val epoch: WireId, val highWater: Revision, val createdAt: String, val expiresAt: String, val pageCount: Int, val pageHashes: List<String>, val manifestHash: String, val changesCursor: String, val snapshotToken: String, override val tree: JValue) : WireMessage { override val definition = "snapshot_manifest"; override fun toString() = "ManifestDto(data=<redacted>)" }
data class PageDto(val snapshot: WireId, val pageIndex: Int, val records: List<RecordDto>, override val tree: JValue) : WireMessage { override val definition = "snapshot_page"; override fun toString() = "PageDto(data=<redacted>)" }
data class BootstrapDto(val account: WireId?, val displayName: String, val createdAt: String?, override val definition: String, override val tree: JValue) : WireMessage { override fun toString() = "BootstrapDto(data=<redacted>)" }
data class InviteRoutingDto(val token: String?, val group: WireId?, val invite: WireId?, val inviteVersion: Revision?, val epoch: WireId?, override val definition: String, override val tree: JValue) : WireMessage { override fun toString() = "InviteRoutingDto(<redacted>)" }
data class ErrorResponseDto(val error: ProtocolError, val request: WireId, val retryAfterSeconds: Int?, override val tree: JValue) : WireMessage { override val definition = "error_response" }
data class RecordMessageDto(val record: RecordDto, override val definition: String, override val tree: JValue) : WireMessage { override fun toString() = "RecordMessageDto(data=<redacted>)" }

internal object ResponseDecoder {
    private fun Map<String,JValue>.id(k: String) = WireId(getValue(k).str())
    private fun Map<String,JValue>.rev(k: String) = Revision(getValue(k).str())
    private fun Map<String,JValue>.optional(k: String) = get(k)?.takeUnless { it == JNull }
    private fun error(v: JValue): ProtocolError = v.obj().let { ProtocolError(it.getValue("code").str(),it.getValue("retryable").bool()) }
    private fun record(v: JValue): RecordDto = v.obj().let { f -> RecordDto(f.getValue("type").str(),f.optional("id")?.let { WireId(it.str()) },f.optional("account_id")?.let { WireId(it.str()) },f.rev("version"),f.optional("membership_generation")?.let { WireId(it.str()) },v) }
    private fun records(f: Map<String,JValue>) = f["records"]?.arr()?.map(::record).orEmpty()
    private fun receipt(definition: String, v: JValue): ReceiptDto = v.obj().let { f ->
        ReceiptDto(f.id("operation_id"),f.getValue("status").str(),f.optional("request_hash")?.str(),f.optional("receipt_id")?.let { WireId(it.str()) },f.optional("replayed")?.bool(),f.optional("entity_version")?.let { Revision(it.str()) },f.optional("group_revision")?.let { Revision(it.str()) },f.optional("feed_epoch")?.let { WireId(it.str()) },records(f),f["error"]?.let(::error),definition,v)
    }
    fun decode(definition: String, v: JValue): WireMessage {
        val f=v.obj()
        return when (definition) {
            "receipt", "operation_lookup", "accepted", "terminal_receipt", "revoked_receipt", "unknown_operation" -> receipt(definition,v)
            "batch_response" -> ResultsDto(f.getValue("results").arr().map { result -> val r=result.obj(); if ("status" in r) ResultDto(r.id("operation_id"),receipt("receipt",result),null,null) else ResultDto(r.id("operation_id"),null,error(r.getValue("error")),r.optional("retry_after_seconds")?.int()) },v)
            "feed" -> FeedDto(f.id("group_id"),f.id("membership_generation"),f.id("feed_epoch"),f.rev("after_revision"),f.rev("high_water_revision"),f.getValue("events").arr().map { event -> event.obj().let { e -> EventDto(e.rev("revision"),e.id("operation_id"),e.id("actor_account_id"),e.getValue("committed_at").str(),e.getValue("action").str(),records(e)) } },f.getValue("has_more").bool(),f.getValue("next_cursor").str(),v)
            "discovery" -> DiscoveryDto(f.id("account_id"),f.id("feed_epoch"),f.rev("after_revision"),f.rev("high_water_revision"),f.getValue("changes").arr().map { change -> change.obj().let { c -> DiscoveryChange(c.rev("revision"),c.id("group_id"),c.id("membership_generation"),c.getValue("status").str(),c.rev("membership_version"),c.optional("role")?.str()) } },f.getValue("has_more").bool(),f.getValue("next_cursor").str(),v)
            "snapshot_manifest" -> ManifestDto(f.id("snapshot_id"),f.id("group_id"),f.id("account_id"),f.id("membership_generation"),f.id("feed_epoch"),f.rev("high_water_revision"),f.getValue("created_at").str(),f.getValue("expires_at").str(),f.getValue("page_count").int(),f.getValue("page_hashes").arr().map { it.str() },f.getValue("manifest_hash").str(),f.getValue("changes_cursor").str(),f.getValue("snapshot_token").str(),v)
            "snapshot_page" -> PageDto(f.id("snapshot_id"),f.getValue("page_index").int(),records(f),v)
            "bootstrap_request", "bootstrap_response" -> BootstrapDto(f.optional("account_id")?.let { WireId(it.str()) },f.getValue("display_name").str(),f.optional("created_at")?.str(),definition,v)
            "invite_resolve_request", "invite_resolution" -> InviteRoutingDto(f.optional("token")?.str(),f.optional("group_id")?.let { WireId(it.str()) },f.optional("invite_id")?.let { WireId(it.str()) },f.optional("invite_version")?.let { Revision(it.str()) },f.optional("feed_epoch")?.let { WireId(it.str()) },definition,v)
            "error_response" -> ErrorResponseDto(error(f.getValue("error")),f.id("request_id"),f.optional("retry_after_seconds")?.int(),v)
            "group", "participant", "membership", "invite", "expense" -> RecordMessageDto(record(v),definition,v)
            else -> DocumentDto(definition,v)
        }
    }
}
