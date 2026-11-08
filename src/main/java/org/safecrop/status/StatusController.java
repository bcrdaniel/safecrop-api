package org.safecrop.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    @GetMapping
    public StatusResponse status() {
        return new StatusResponse("SafeCrop API", "UP");
    }

    public record StatusResponse(String application, String status) {
    }
}
