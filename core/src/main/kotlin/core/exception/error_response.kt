package core.exception

import com.fasterxml.jackson.annotation.JsonProperty


enum class ReqError(val errorCodeStr: String) {
    ENTITY_WITH_ID_NOT_FOUND("ENTITY_WITH_ID_NOT_FOUND"),
    ROLE_NOT_ALLOWED("ROLE_NOT_ALLOWED")
}


data class RequestErrorResponse(
        @JsonProperty("id", required = true) val id: String,
        @JsonProperty("code", required = true) val code: String,
        @JsonProperty("attrs", required = true) val attrs: Map<String, String>,
        @JsonProperty("log_msg", required = true) val logMsg: String
)
