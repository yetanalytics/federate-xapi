package com.yetanalytics.hlaxapi.validation;

import com.yetanalytics.hlaxapi.config.ConfigVersions;
import com.yetanalytics.hlaxapi.validation.ValidationModels.CapabilitiesResponse;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ValidationRequest;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ValidationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/validation", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Validation")
public final class ValidationController {

    private final ValidationCore validationCore;

    public ValidationController(ValidationCore validationCore) {
        this.validationCore = validationCore;
    }

    @GetMapping("/capabilities")
    @Operation(summary = "Describe validator readiness and supported contracts")
    public CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(
                true,
                ValidationCore.API_VERSION,
                validatorVersion(),
                ConfigVersions.SUPPORTED.stream().sorted().toList(),
                List.of(
                        "fom-catalog",
                        "injection-validation",
                        "statement-rendering",
                        "xapi-validation",
                        "subscription-analysis"));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Validate a complete in-memory FOM and xAPI configuration")
    public ValidationResponse validate(@RequestBody ValidationRequest request) {
        return validationCore.validate(request);
    }

    private static String validatorVersion() {
        String version = ValidationController.class.getPackage().getImplementationVersion();
        return version == null ? "1.0-SNAPSHOT" : version;
    }
}
