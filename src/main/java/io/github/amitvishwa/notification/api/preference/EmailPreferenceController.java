package io.github.amitvishwa.notification.api.preference;

import io.github.amitvishwa.notification.api.preference.dto.PreferenceCreateRequest;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceLookupRequest;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceResponse;
import io.github.amitvishwa.notification.api.preference.dto.PreferenceUpdateRequest;
import io.github.amitvishwa.notification.api.security.AuthenticatedCaller;
import io.github.amitvishwa.notification.application.preference.PreferenceManagementService;
import io.github.amitvishwa.notification.application.preference.PreferenceResult;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/email-preferences")
public class EmailPreferenceController {

    private final PreferenceManagementService preferences;

    public EmailPreferenceController(
            PreferenceManagementService preferences
    ) {
        this.preferences = preferences;
    }

    @PostMapping
    public ResponseEntity<PreferenceResponse> create(
            @RequestAttribute(AuthenticatedCaller.REQUEST_ATTRIBUTE)
            AuthenticatedCaller caller,

            @Valid @RequestBody
            PreferenceCreateRequest request
    ) {
        PreferenceResult result = preferences.create(
                PreferenceApiMapper.toKey(
                        caller.sourceApplication(),
                        request.recipientEmail()
                ),
                PreferenceApiMapper.toSettings(request.settings())
        );

        URI location = URI.create(
                "/api/v1/email-preferences/" + result.preferenceId()
        );

        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(PreferenceApiMapper.toResponse(result));
    }

    @PostMapping("/lookup")
    public ResponseEntity<PreferenceResponse> lookup(
            @RequestAttribute(AuthenticatedCaller.REQUEST_ATTRIBUTE)
            AuthenticatedCaller caller,

            @Valid @RequestBody
            PreferenceLookupRequest request
    ) {
        PreferenceResult result = preferences.lookup(
                PreferenceApiMapper.toKey(
                        caller.sourceApplication(),
                        request.recipientEmail()
                )
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PreferenceApiMapper.toResponse(result));
    }

    @GetMapping("/{preferenceId}")
    public ResponseEntity<PreferenceResponse> findById(
            @RequestAttribute(AuthenticatedCaller.REQUEST_ATTRIBUTE)
            AuthenticatedCaller caller,

            @PathVariable("preferenceId")
            UUID preferenceId
    ) {
        PreferenceResult result = preferences.findById(
                preferenceId,
                caller.sourceApplication()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PreferenceApiMapper.toResponse(result));
    }

    @PutMapping("/{preferenceId}")
    public ResponseEntity<PreferenceResponse> update(
            @RequestAttribute(AuthenticatedCaller.REQUEST_ATTRIBUTE)
            AuthenticatedCaller caller,

            @PathVariable("preferenceId")
            UUID preferenceId,

            @Valid @RequestBody
            PreferenceUpdateRequest request
    ) {
        PreferenceResult result = preferences.update(
                preferenceId,
                caller.sourceApplication(),
                request.expectedVersion(),
                PreferenceApiMapper.toSettings(request.settings())
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PreferenceApiMapper.toResponse(result));
    }
}