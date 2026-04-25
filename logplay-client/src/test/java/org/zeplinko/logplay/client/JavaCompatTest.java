package org.zeplinko.logplay.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.zeplinko.logplay.client.codec.Codecs;
import org.zeplinko.logplay.client.codec.JacksonPayloadCodec;
import org.zeplinko.logplay.client.codec.PayloadCodec;
import org.zeplinko.logplay.client.codec.TypeToken;
import org.zeplinko.logplay.client.handler.HandlerFactory;
import org.zeplinko.logplay.client.handler.JobContext;
import org.zeplinko.logplay.client.handler.JobHandler;
import org.zeplinko.logplay.client.integration.FakeBookingService;
import org.zeplinko.logplay.client.integration.TripBookingRequest;
import org.zeplinko.logplay.client.model.CreateJobRequest;
import org.zeplinko.logplay.client.model.Job;
import org.zeplinko.logplay.client.worker.Worker;
import org.zeplinko.logplay.client.worker.WorkerConfig;

/**
 * Compile-time guarantee that the public SDK surface is callable from idiomatic Java. If this
 * file stops compiling, the public API broke for Java users.
 */
class JavaCompatTest {

    @Test
    void buildsManagerAndWorker() {
        LogPlayManager manager =
                LogPlayManager.builder()
                        .baseUrl("http://localhost:8080")
                        .groupId("group-1")
                        .build();
        // Build a typed codec via Class<T>.
        ObjectMapper mapper = new ObjectMapper().registerModule(new KotlinModule.Builder().build());
        PayloadCodec<String> stringCodec = JacksonPayloadCodec.of(mapper, String.class);
        assertThat(stringCodec).isNotNull();

        // TypeToken anonymous-subclass form works from Java.
        TypeToken<java.util.List<String>> token = new TypeToken<>() {
        };
        assertThat(token.getType()).isNotNull();

        // Java lambda for JobHandler — proves it's a true functional interface.
        JobHandler<String, String> handler =
                (input, ctx) -> {
                    // Replay-able step via Class<T> overload.
                    String greeted = ctx.run("greet", String.class, () -> "hello " + input);
                    ctx.runVoid("logIt", () -> {});
                    return greeted;
                };
        assertThat(handler).isNotNull();

        Worker worker =
                manager.newWorker("worker-1", WorkerConfig.defaults())
                        .registerHandler("greet", Codecs.UTF8, Codecs.UTF8, handler);
        assertThat(worker.workerId()).isEqualTo("worker-1");
        assertThat(worker.groupId()).isEqualTo("group-1");
    }

    @Test
    void typedRequestAndHandlerAreJavaErgonomic() {
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        // Typed input: Class-based factory commits <String>; .input(value) is a plain setter.
        CreateJobRequest<String> req =
                CreateJobRequest.builder(String.class)
                        .type("t").idempotencyKey("k")
                        .input("hello")
                        .build();
        assertThat(req).isNotNull();

        // Class-based registerHandler — Java's most ergonomic JSON path.
        JobHandler<String, String> handler = (input, ctx) -> "echo:" + input;
        Worker worker =
                manager.newWorker("worker-1", WorkerConfig.defaults())
                        .registerHandler("t", String.class, String.class, handler);
        assertThat(worker).isNotNull();
    }

    @Test
    void handlerCanReturnAndReceiveNull() {
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        // Java handler accepts null input and may return null. Compiles cleanly under
        // @Nullable on JobHandler.execute params/return.
        JobHandler<String, String> handler =
                (input, ctx) -> input == null ? null : "echo:" + input;
        Worker worker =
                manager.newWorker("worker-1", WorkerConfig.defaults())
                        .registerHandler("t", String.class, String.class, handler);
        assertThat(worker).isNotNull();
    }

    @Test
    void diStyleHandlerFactoryRegistration() {
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        // Simulate a DI container — in real code this would be applicationContext or similar.
        JobHandler<String, String> beanLikeHandler = (input, ctx) -> "bean:" + input;

        // DI-style factory typed inline. This proves users can write
        //   () -> applicationContext.getBean(MyHandler.class)
        // without explicit casting — lambda arity (zero-arg) disambiguates from JobHandler.
        HandlerFactory<String, String> factory = () -> beanLikeHandler;

        Worker worker =
                manager.newWorker("worker-1", WorkerConfig.defaults())
                        .registerHandler("t", String.class, String.class, factory);
        assertThat(worker).isNotNull();
    }

    @Test
    void createJobAcceptsGroupIdOverrideFromJava() {
        // Proves the (req, groupIdOverride) blocking overload is callable from Java without casting.
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("default").build();
        CreateJobRequest<String> req =
                CreateJobRequest.builder(String.class)
                        .type("t").idempotencyKey("k")
                        .input("hello")
                        .build();
        // Don't actually submit (no server) — just prove the overload resolves and returns Job.
        Supplier<Job> call = () -> manager.createJob(req, "other-group");
        assertThat(call).isNotNull();
    }

    @Test
    void registerHandlerWithTypeTokenAndFactoryFromJava() {
        // TypeToken-form registerHandler (both single-instance and factory variants) must
        // resolve cleanly in Java for parameterized generic payloads.
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        TypeToken<java.util.List<String>> in = new TypeToken<>() {};
        TypeToken<java.util.List<String>> out = new TypeToken<>() {};

        JobHandler<java.util.List<String>, java.util.List<String>> handler =
                (input, ctx) -> input == null ? java.util.List.of() : input;
        HandlerFactory<java.util.List<String>, java.util.List<String>> factory =
                () -> (input, ctx) -> input == null ? java.util.List.of() : input;

        Worker worker =
                manager.newWorker("w", WorkerConfig.defaults())
                        .registerHandler("t-handler", in, out, handler)
                        .registerHandler("t-factory", in, out, factory);
        assertThat(worker).isNotNull();
    }

    @Test
    void noInputBuilderProducesNothingTypedRequest() {
        // The no-input factory returns Builder<Nothing>. Java sees `<Nothing>` as `Void`-like
        // and cannot call .input(...) — the compiler enforces the no-input contract. The raw
        // bytes escape hatch (inputData) lives as a Kotlin extension, not visible to Java —
        // documented limitation; Java users with raw bytes call the codec form instead.
        CreateJobRequest<?> req =
                CreateJobRequest.builder().type("ping").idempotencyKey("k").build();
        assertThat(req.getType()).isEqualTo("ping");
        assertThat(req.getIdempotencyKey()).isEqualTo("k");
        assertThat(req.getInput()).isNull();
    }

    @Test
    void ctxSleepOverloadsAreVisibleFromJava() {
        // Compile-time guarantee: both Duration and long-millis overloads are callable from Java
        // without casting and the handler can declare `throws Exception` to cover them.
        JobHandler<String, String> handler =
                (input, ctx) -> {
                    ctx.sleep("backoff", java.time.Duration.ofSeconds(2));
                    ctx.sleep("backoff-ms", 250L);
                    return input;
                };
        assertThat(handler).isNotNull();
    }

    @Test
    void blockingLifecycleMethodsAreCallableFromJava() {
        // Proves the blocking/lifecycle helpers resolve from Java with the right shapes:
        //   - awaitTermination()      -> void, declares checked InterruptedException
        //   - awaitTermination(long)  -> boolean, declares checked InterruptedException
        //   - installShutdownHook()   -> Worker (chainable)
        // Captured in functional wrappers (never invoked) so the test neither blocks forever nor
        // registers a real JVM shutdown hook — same "just prove it resolves" pattern used above.
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        Worker worker = manager.newWorker("w", WorkerConfig.defaults());

        Supplier<Worker> hookInstall = worker::installShutdownHook;
        Runnable blocking =
                () -> {
                    try {
                        worker.awaitTermination();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        BooleanSupplier bounded =
                () -> {
                    try {
                        return worker.awaitTermination(1L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                };

        assertThat(hookInstall).isNotNull();
        assertThat(blocking).isNotNull();
        assertThat(bounded).isNotNull();
    }

    @Test
    void jsonCodecHelperOnManagerIsCallableFromJava() {
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        PayloadCodec<String> codec = manager.jsonCodec(String.class);
        assertThat(codec).isNotNull();
        assertThat(codec.encode("hi")).isNotNull();
    }

    // --- Post-Kotlin-conversion compat coverage ----------------------------------------------
    // JobHandler, JobContext, and HandlerFactory moved from Java interfaces to Kotlin
    // `fun interface` (JobHandler, HandlerFactory) and `interface` (JobContext). These tests
    // exercise the Java view to catch any regressions: SAM conversion, generic inference,
    // null-permissive return types (no @NotNull on generic positions in bytecode), and
    // class-based implementations.

    @Test
    void concreteJobHandlerClassRegistersOnWorker() {
        // BookTravelHandler is a Java class that implements the Kotlin JobHandler<I, O> fun
        // interface. Registering it on the Worker via the Class-based overload proves the cross-
        // language type bridge still compiles cleanly: TripBookingRequest comes from Kotlin
        // (a data class in test sources), JobHandler is now Kotlin, and the worker's typed
        // registerHandler resolves the generics end-to-end.
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        BookTravelHandler handler = new BookTravelHandler(new FakeBookingService());
        Worker worker =
                manager.newWorker("w", WorkerConfig.defaults())
                        .registerHandler(
                                "travel", TripBookingRequest.class, String.class, handler);
        assertThat(worker).isNotNull();
    }

    @Test
    void bookTravelHandlerExecuteRunsAllStepsAgainstMockJobContext() throws Exception {
        // Actually invoke BookTravelHandler.execute against a mock JobContext. Exercises every
        // ctx.run(String, Class<T>, Callable<T>) call site from Java post-conversion. If the
        // Kotlin interface's generic signature degraded (e.g., raw Class lost type inference),
        // this test would fail at compile time or runtime.
        JobContext ctx = mock(JobContext.class);
        // Mock the Class<T> overload: invoke the supplied Callable and return its value.
        when(ctx.run(anyString(), any(Class.class), any(Callable.class)))
                .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(2)).call());

        FakeBookingService service = new FakeBookingService();
        BookTravelHandler handler = new BookTravelHandler(service);
        TripBookingRequest req = new TripBookingRequest("rider-42", "NYC");

        String result = handler.execute(req, ctx);

        assertThat(result)
                .isEqualTo(
                        "doc-rider-42/flight-rider-42/hotel-rider-42/cab-rider-42/txn-rider-42/notif-rider-42");
        // Every service method should have been invoked exactly once — the handler walked
        // through all six checkpointed steps via ctx.run.
        assertThat(service.getValidatePassportCalls().get()).isEqualTo(1);
        assertThat(service.getBookFlightCalls().get()).isEqualTo(1);
        assertThat(service.getBookHotelCalls().get()).isEqualTo(1);
        assertThat(service.getBookCabCalls().get()).isEqualTo(1);
        assertThat(service.getChargePaymentCalls().get()).isEqualTo(1);
        assertThat(service.getSendItineraryCalls().get()).isEqualTo(1);
    }

    @Test
    void handlerFactoryProducesFreshBookTravelHandlerPerInvocation() {
        // DI-scoped registration pattern: HandlerFactory lambda materializes a new
        // BookTravelHandler per job. Proves the Kotlin HandlerFactory fun interface still
        // SAM-converts cleanly from Java post-conversion, and that calling create() returns the
        // Java concrete class through the Kotlin interface boundary.
        FakeBookingService service = new FakeBookingService();
        HandlerFactory<TripBookingRequest, String> factory =
                () -> new BookTravelHandler(service);

        JobHandler<TripBookingRequest, String> first = factory.create();
        JobHandler<TripBookingRequest, String> second = factory.create();

        assertThat(first).isInstanceOf(BookTravelHandler.class);
        assertThat(second).isInstanceOf(BookTravelHandler.class);
        // Each create() returns a fresh instance — what the DI-style contract requires.
        assertThat(first).isNotSameAs(second);

        // And the factory can be registered on a Worker via the Class form.
        LogPlayManager manager =
                LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build();
        Worker worker =
                manager.newWorker("w", WorkerConfig.defaults())
                        .registerHandler(
                                "travel", TripBookingRequest.class, String.class, factory);
        assertThat(worker).isNotNull();
    }
}