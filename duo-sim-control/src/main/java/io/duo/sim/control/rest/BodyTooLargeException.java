package io.duo.sim.control.rest;

import java.io.IOException;

/** 请求体超出 {@link RestControlServer#MAX_BODY_BYTES}（管道映射 413，有界排空后必达）。 */
final class BodyTooLargeException extends IOException {
    BodyTooLargeException() {
        super("request body exceeds " + RestControlServer.MAX_BODY_BYTES + " bytes");
    }
}
