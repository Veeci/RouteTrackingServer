package veeci.practicing.rts.protocol

import kotlinx.serialization.json.Json

/** The JSON settings of the wire protocol. Server and SDK must both use exactly these. */
val ProtocolJson: Json =
    Json {
        // {"type": "error", ...}: the field that says which message a frame holds.
        classDiscriminator = "type"
        // An older reader tolerates fields added by a newer writer (forward compatibility).
        ignoreUnknownKeys = true
        // Absent optional fields are left out instead of being sent as null.
        explicitNulls = false
        // Default values are written out ("mock": false), so a frame in a log shows every value the reader will use.
        encodeDefaults = true
    }
