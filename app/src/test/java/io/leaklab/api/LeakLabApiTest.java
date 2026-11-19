package io.leaklab.api;

import io.leaklab.leak.CustomerSessionCache;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class LeakLabApiTest {

    @AfterEach
    void release() {
        given().post("/api/memory/release").then().statusCode(200);
    }

    @Test
    void telemetryExposesEveryMemoryLayerAndAVerdict() {
        given().get("/api/telemetry").then()
                .statusCode(200)
                .body("memory.heap.max", greaterThan(0L))
                .body("memory.nonHeap.committed", greaterThan(0))
                .body("memory.pools.size()", greaterThan(0))
                .body("memory.verdict.level", notNullValue())
                .body("runtime.flavor", notNullValue());
    }

    @Test
    void loadSettingsAreClampedToSafeRanges() {
        given().contentType("application/json")
                .body("{\"running\":false,\"mode\":\"NATIVE\",\"ratePerSecond\":99999,\"payloadKb\":0,\"retentionPercent\":250}")
                .put("/api/load").then()
                .statusCode(200)
                .body("mode", equalTo("NATIVE"))
                .body("ratePerSecond", equalTo(2000))
                .body("payloadKb", equalTo(1))
                .body("retentionPercent", equalTo(100));
    }

    @Test
    void keepRunningOnOomSurvivesRequestsThatOmitIt() {
        String base = "\"running\":false,\"mode\":\"HEAP\",\"ratePerSecond\":10,\"payloadKb\":1,\"retentionPercent\":0";
        given().contentType("application/json").body("{" + base + ",\"keepRunningOnOom\":true}")
                .put("/api/load").then().statusCode(200).body("keepRunningOnOom", equalTo(true));
        // a client that predates the field (the verification scripts, curl from the docs) must not reset it
        given().contentType("application/json").body("{" + base + "}")
                .put("/api/load").then().statusCode(200).body("keepRunningOnOom", equalTo(true));
        given().contentType("application/json").body("{" + base + ",\"keepRunningOnOom\":false}")
                .put("/api/load").then().statusCode(200).body("keepRunningOnOom", equalTo(false));
    }

    @Test
    void burstRetainsAndReleaseFrees() {
        given().contentType("application/json").body("{\"mode\":\"HEAP\",\"megabytes\":8}")
                .post("/api/load/burst").then().statusCode(200).body("megabytes", equalTo(8));
        assertEquals(8L << 20, CustomerSessionCache.payloadBytes());

        given().post("/api/memory/release").then().statusCode(200).body("heapBytes", equalTo(8 << 20));
        assertEquals(0, CustomerSessionCache.size());
    }

    @Test
    void heapDumpIsWrittenAndListed() {
        String name = given().post("/api/dumps/heap").then()
                .statusCode(200)
                .body("kind", equalTo("heap"))
                .body("sizeBytes", greaterThanOrEqualTo(0))
                .extract().path("name");
        given().get("/api/dumps").then().statusCode(200).body("name", org.hamcrest.Matchers.hasItem(name));
    }

    @Test
    void downloadRejectsPathTraversal() {
        given().get("/api/dumps/..%2Fpom.xml/download").then().statusCode(404);
    }

    @Test
    void prometheusExposesLeakGauges() {
        given().get("/q/metrics").then().statusCode(200).body(containsString("leaklab_retained_bytes"));
    }
}
