package org.zalando.nakadi.webservice;

import com.google.common.collect.ImmutableList;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Header;
import com.jayway.restassured.response.Response;
import com.jayway.restassured.specification.RequestSpecification;
import org.echocat.jomon.runtime.concurrent.RetryForSpecifiedTimeStrategy;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.Subscription;
import org.zalando.nakadi.domain.SubscriptionBase;
import org.zalando.nakadi.utils.RandomSubscriptionBuilder;
import org.zalando.nakadi.utils.TestUtils;
import org.zalando.nakadi.webservice.hila.StreamBatch;
import org.zalando.nakadi.webservice.utils.NakadiTestUtils;
import org.zalando.nakadi.webservice.utils.TestStreamingClient;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.jayway.restassured.http.ContentType.JSON;
import static java.util.stream.IntStream.rangeClosed;
import static org.echocat.jomon.runtime.concurrent.Retryer.executeWithRetry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;
import static org.zalando.nakadi.domain.SubscriptionBase.InitialPosition.BEGIN;
import static org.zalando.nakadi.utils.TestUtils.getEventTypeJsonFromFile;
import static org.zalando.nakadi.utils.TestUtils.randomTextString;
import static org.zalando.nakadi.utils.TestUtils.waitFor;
import static org.zalando.nakadi.webservice.utils.NakadiTestUtils.commitCursors;
import static org.zalando.nakadi.webservice.utils.NakadiTestUtils.createEventType;
import static org.zalando.nakadi.webservice.utils.NakadiTestUtils.createSubscription;
import static org.zalando.nakadi.webservice.utils.NakadiTestUtils.publishEvent;

public class UserJourneyAT extends RealEnvironmentAT {

    private static final String TEST_EVENT_TYPE = TestUtils.randomValidEventTypeName();

    private static final String EVENT1 = "{\"foo\":\"" + randomTextString() + "\"}";
    private static final String EVENT2 = "{\"foo\":\"" + randomTextString() + "\"}";

    private String eventTypeBody;
    private String eventTypeBodyUpdate;

    @Before
    public void before() throws IOException {
        eventTypeBody = getEventTypeJsonFromFile("sample-event-type.json", TEST_EVENT_TYPE);
        eventTypeBodyUpdate = getEventTypeJsonFromFile("sample-event-type-update.json", TEST_EVENT_TYPE);
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 15000)
    public void userJourneyM1() throws InterruptedException {

        // create event-type
        jsonRequestSpec()
                .body(eventTypeBody)
                .when()
                .post("/event-types")
                .then()
                .statusCode(CREATED.value());

        // get event type
        jsonRequestSpec()
                .when()
                .get("/event-types/" + TEST_EVENT_TYPE)
                .then()
                .statusCode(OK.value())
                .body("name", equalTo(TEST_EVENT_TYPE))
                .body("owning_application", equalTo("stups_nakadi"))
                .body("category", equalTo("undefined"))
                .body("schema.type", equalTo("json_schema"))
                .body("schema.schema", equalTo("{\"type\": \"object\", \"properties\": " +
                        "{\"foo\": {\"type\": \"string\"}}, \"required\": [\"foo\"]}"));

        // list event types
        jsonRequestSpec()
                .when()
                .get("/event-types")
                .then()
                .statusCode(OK.value())
                .body("size()", Matchers.greaterThan(0))
                .body("name[0]", notNullValue())
                .body("owning_application[0]", notNullValue())
                .body("category[0]", notNullValue())
                .body("schema.type[0]", notNullValue())
                .body("schema.schema[0]", notNullValue());

        // update event-type
        jsonRequestSpec()
                .body(eventTypeBodyUpdate)
                .when()
                .put("/event-types/" + TEST_EVENT_TYPE)
                .then()
                .statusCode(OK.value());

        // Updates should eventually cause a cache invalidation, so we must retry
        executeWithRetry(() -> {
                    // get event type to check that update is done
                    jsonRequestSpec()
                            .when()
                            .get("/event-types/" + TEST_EVENT_TYPE)
                            .then()
                            .statusCode(OK.value())
                            .body("owning_application", equalTo("my-app"));
                },
                new RetryForSpecifiedTimeStrategy<Void>(5000)
                        .withExceptionsThatForceRetry(AssertionError.class)
                        .withWaitBetweenEachTry(500));

        // push two events to event-type
        postEvents(new String[]{EVENT1, EVENT2});

        // get offsets for partition
        jsonRequestSpec()
                .when()
                .get("/event-types/" + TEST_EVENT_TYPE + "/partitions/0")
                .then()
                .statusCode(OK.value())
                .body("partition", equalTo("0"))
                .body("oldest_available_offset", equalTo("0"))
                .body("newest_available_offset", equalTo("1"));

        // get offsets for all partitions
        jsonRequestSpec()
                .when()
                .get("/event-types/" + TEST_EVENT_TYPE + "/partitions")
                .then()
                .statusCode(OK.value())
                .body("size()", equalTo(1)).body("partition[0]", notNullValue())
                .body("oldest_available_offset[0]", notNullValue())
                .body("newest_available_offset[0]", notNullValue());

        // read events
        requestSpec()
                .header(new Header("X-nakadi-cursors", "[{\"partition\": \"0\", \"offset\": \"BEGIN\"}]"))
                .param("batch_limit", "2")
                .param("stream_limit", "2")
                .when()
                .get("/event-types/" + TEST_EVENT_TYPE + "/events")
                .then()
                .statusCode(OK.value())
                .body(equalTo("{\"cursor\":{\"partition\":\"0\",\"offset\":\"1\"},\"events\":" + "[" + EVENT1 + ","
                        + EVENT2 + "]}\n"));

        // delete event type
        jsonRequestSpec()
                .when()
                .delete("/event-types/" + TEST_EVENT_TYPE)
                .then()
                .statusCode(OK.value());

        // check that it was removed
        jsonRequestSpec()
                .when()
                .get("/event-types/" + TEST_EVENT_TYPE)
                .then()
                .statusCode(NOT_FOUND.value());
    }

    public void userJourneyHila() throws InterruptedException, IOException {
        final EventType eventType = createEventType();

        rangeClosed(0, 3)
                .forEach(x -> publishEvent(eventType.getName(), "{\"blah\":\"foo" + x + "\"}"));

        final SubscriptionBase subscriptionToCreate = RandomSubscriptionBuilder.builder()
                .withEventType(eventType.getName())
                .withStartFrom(BEGIN)
                .buildSubscriptionBase();
        final Subscription subscription = createSubscription(subscriptionToCreate);

        final TestStreamingClient client = TestStreamingClient
                .create(RestAssured.baseURI + ":" + RestAssured.port, subscription.getId(), "")
                .start();
        waitFor(() -> assertThat(client.getBatches(), hasSize(4)));
        final String sessionId = client.getSessionId();

        final Response subscriptionStat = NakadiTestUtils.getSubscriptionStat(subscription);
        subscriptionStat.then()
                .statusCode(OK.value());

        final List<StreamBatch> batches = client.getBatches();
        final StreamBatch lastBatch = batches.get(batches.size() - 1);
        final int commitCode = commitCursors(subscription.getId(), ImmutableList.of(lastBatch.getCursor()), sessionId);
        assertThat(commitCode, equalTo(OK.value()));


    }

    private void postEvents(final String[] events) {
        final String batch = "[" + String.join(",", events) + "]";
        jsonRequestSpec()
                .body(batch)
                .when()
                .post("/event-types/" + TEST_EVENT_TYPE + "/events")
                .then()
                .statusCode(OK.value());
    }

    private RequestSpecification jsonRequestSpec() {
        return requestSpec()
                .header("accept", "application/json")
                .contentType(JSON);
    }

    private static String timeString() {
        return DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss_SSS").format(LocalDateTime.now());
    }

}
