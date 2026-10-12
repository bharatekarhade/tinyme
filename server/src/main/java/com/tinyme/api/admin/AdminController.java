package com.tinyme.api.admin;

import com.tinyme.domain.admin.ExportService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final ExportService exports;

    public AdminController(ExportService exports) {
        this.exports = exports;
    }

    @GetMapping(value = "/export", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> export() {
        if (!exports.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An export is already running");
        }
        try {
            String filename = exports.filename();
            StreamingResponseBody body = output -> {
                try {
                    exports.writeArchive(output);
                } finally {
                    exports.release();
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .cacheControl(CacheControl.noStore())
                    .body(body);
        } catch (RuntimeException error) {
            exports.release();
            throw error;
        }
    }
}
