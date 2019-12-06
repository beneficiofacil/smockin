package com.smockin.mockserver.engine;

import com.smockin.admin.persistence.dao.RestfulMockDAO;
import com.smockin.admin.persistence.entity.RestfulMock;
import com.smockin.admin.persistence.enums.SmockinUserRoleEnum;
import com.smockin.admin.websocket.LiveLoggingHandler;
import com.smockin.mockserver.dto.MockServerState;
import com.smockin.mockserver.dto.MockedServerConfigDTO;
import com.smockin.mockserver.dto.ProxyActiveMock;
import com.smockin.mockserver.exception.MockServerException;
import com.smockin.mockserver.proxy.ProxyServer;
import com.smockin.mockserver.service.*;
import com.smockin.mockserver.service.ws.SparkWebSocketEchoService;
import com.smockin.utils.GeneralUtils;
import com.smockin.utils.LiveLoggingUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import spark.Request;
import spark.Response;
import spark.Spark;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by mgallina.
 */
@Service
@Transactional
public class MockedRestServerEngine implements MockServerEngine<MockedServerConfigDTO, Optional<Void>> {

    private final Logger logger = LoggerFactory.getLogger(MockedRestServerEngine.class);

    @Autowired
    private RestfulMockDAO restfulMockDAO;

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private HttpProxyService proxyService;

    @Autowired
    private MockOrderingCounterService mockOrderingCounterService;

    @Autowired
    private InboundParamMatchService inboundParamMatchService;

    @Autowired
    private WebSocketService webSocketService;

    @Autowired
    private ServerSideEventService serverSideEventService;

    @Autowired
    private ProxyServer proxyServer;

    @Autowired
    private MockedRestServerEngineUtils mockedRestServerEngineUtils;

    @Autowired
    private LiveLoggingHandler liveLoggingHandler;

    private final Object monitor = new Object();
    private MockServerState serverState = new MockServerState(false, 0);

    @Override
    public void start(final MockedServerConfigDTO config, final Optional opt) throws MockServerException {
        logger.debug("start called");

        initServerConfig(config);

        // Define all web socket routes first as the Spark framework requires this
        buildWebSocketEndpoints();

        // Handle Cross-Origin Resource Sharing (CORS) support
        handleCORS(config);

        // Next handle all HTTP RESTFul web service routes
        buildGlobalHttpEndpointsHandler();

        applyTrafficLogging();

        initServer(config.getPort());

//        initProxyServer(activeRestfulMocks, config);

    }

    @Override
    public MockServerState getCurrentState() throws MockServerException {
        synchronized (monitor) {
            return serverState;
        }
    }

    @Override
    public void shutdown() throws MockServerException {

        try {

            serverSideEventService.interruptAndClearAllHeartBeatThreads();

            Spark.stop();

            // Having dug around the source code, 'Spark.stop()' runs off a different thread when stopping the server and removing it's state such as routes, etc.
            // This means that calling 'Spark.port()' immediately after stop, results in an IllegalStateException, as the
            // 'initialized' flag is checked in the current thread and is still marked as true.
            // (The error thrown: java.lang.IllegalStateException: This must be done before route mapping has begun)
            // Short of editing the Spark source to fix this, I have therefore had to add this hack to buy the 'stop' thread time to complete.
            Thread.sleep(3000);

            synchronized (monitor) {
                serverState.setRunning(false);
            }

            clearState();

            proxyServer.shutdown();

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    void initServer(final int port) throws MockServerException {
        logger.debug("initServer called");

        try {

            clearState();

            Spark.init();

            // Blocks the current thread (using a CountDownLatch under the hood) until the server is fully initialised.
            Spark.awaitInitialization();

            synchronized (monitor) {
                serverState.setRunning(true);
                serverState.setPort(port);
            }

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    void initServerConfig(final MockedServerConfigDTO config) {
        logger.debug("initServerConfig called");

        if (logger.isDebugEnabled())
            logger.debug(config.toString());

        Spark.port(config.getPort());
        Spark.threadPool(config.getMaxThreads(), config.getMinThreads(), config.getTimeOutMillis());
    }

    void initProxyServer(final List<RestfulMock> activeMocks, final MockedServerConfigDTO config) {

        if (!isProxyServerModeEnabled(config)) {
            return;
        }

        final List<ProxyActiveMock> activeProxyMocks = activeMocks.stream()
                .map(m -> new ProxyActiveMock(m.getPath(), m.getCreatedBy().getCtxPath(), m.getMethod()))
                .collect(Collectors.toList());

        proxyServer.start(config, activeProxyMocks);
    }

    public boolean isProxyServerModeEnabled(final MockedServerConfigDTO config) {
        return BooleanUtils.toBoolean(config.getNativeProperties().get(GeneralUtils.PROXY_SERVER_ENABLED_PARAM));
    }

    void buildWebSocketEndpoints() {

        Spark.webSocket("/*", new SparkWebSocketEchoService(webSocketService));
    }

    private void applyTrafficLogging() {

        // Live logging filter
        Spark.before((request, response) -> {

            if (request.headers().contains(GeneralUtils.PROXY_MOCK_INTERCEPT_HEADER)) {
                return;
            }

            final String traceId = GeneralUtils.generateUUID();

            request.attribute(GeneralUtils.LOG_REQ_ID, traceId);
            response.raw().addHeader(GeneralUtils.LOG_REQ_ID, traceId);

            final Map<String, String> reqHeaders = request.headers()
                    .stream()
                    .collect(Collectors.toMap(h -> h, h -> request.headers(h)));

            reqHeaders.put(GeneralUtils.LOG_REQ_ID, traceId);

            liveLoggingHandler.broadcast(LiveLoggingUtils.buildLiveLogInboundDTO(request.attribute(GeneralUtils.LOG_REQ_ID), request.requestMethod(), request.pathInfo(), reqHeaders, request.body(), false));
        });

        Spark.afterAfter((request, response) -> {

            if (request.headers().contains(GeneralUtils.PROXY_MOCK_INTERCEPT_HEADER)
                    || serverSideEventService.SSE_EVENT_STREAM_HEADER.equals(response.raw().getHeader("Content-Type"))) {
                return;
            }

            final Map<String, String> respHeaders = response.raw().getHeaderNames()
                    .stream()
                    .collect(Collectors.toMap(h -> h, h -> response.raw().getHeader(h)));

            respHeaders.put(GeneralUtils.LOG_REQ_ID, request.attribute(GeneralUtils.LOG_REQ_ID));

            liveLoggingHandler.broadcast(LiveLoggingUtils.buildLiveLogOutboundDTO(request.attribute(GeneralUtils.LOG_REQ_ID), response.raw().getStatus(), respHeaders, response.body(), false, false));
        });

    }

    void buildGlobalHttpEndpointsHandler() {
        logger.debug("buildGlobalHttpEndpointsHandler called");

        final String wildcardPath = "*";

        Spark.head(wildcardPath, (request, response) ->
                mockedRestServerEngineUtils.loadMockedResponse(request, response)
                        .orElseGet(() -> handleNotFoundResponse(response)));

        Spark.get(wildcardPath, (request, response) -> {

            if (isWebSocketUpgradeRequest(request)) {
                response.status(HttpStatus.OK.value());
                return null;
            }

            return mockedRestServerEngineUtils.loadMockedResponse(request, response)
                    .orElseGet(() -> handleNotFoundResponse(response));
        });

        Spark.post(wildcardPath, (request, response) ->
                mockedRestServerEngineUtils.loadMockedResponse(request, response)
                        .orElseGet(() -> handleNotFoundResponse(response)));

        Spark.put(wildcardPath, (request, response) ->
                mockedRestServerEngineUtils.loadMockedResponse(request, response)
                        .orElseGet(() -> handleNotFoundResponse(response)));

        Spark.delete(wildcardPath, (request, response) ->
                mockedRestServerEngineUtils.loadMockedResponse(request, response)
                        .orElseGet(() -> handleNotFoundResponse(response)));

        Spark.patch(wildcardPath, (request, response) ->
                mockedRestServerEngineUtils.loadMockedResponse(request, response)
                        .orElseGet(() -> handleNotFoundResponse(response)));

    }

    private String handleNotFoundResponse(final Response response) {

        response.status(HttpStatus.NOT_FOUND.value());
        return "";
    }

    private boolean isWebSocketUpgradeRequest(final Request request) {

        final Set<String> headerNames = request.headers();

        return headerNames.contains("Upgrade")
                && headerNames.contains("Sec-WebSocket-Key")
                && "websocket".equalsIgnoreCase(request.headers("Upgrade"));
    }

    void clearState() {

        // Proxy related state
        webSocketService.clearSession();
        proxyService.clearAllSessions();
        mockOrderingCounterService.clearState();
        serverSideEventService.clearState();

    }

    void handleCORS(final MockedServerConfigDTO config) {

        final String enableCors = config.getNativeProperties().get(GeneralUtils.ENABLE_CORS_PARAM);

        if (!Boolean.TRUE.toString().equalsIgnoreCase(enableCors)) {
            return;
        }

        Spark.options("/*", (request, response) -> {

            final String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");

            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            final String accessControlRequestMethod = request.headers("Access-Control-Request-Method");

            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }

            return HttpStatus.OK.name();
        });

        Spark.before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
        });

    }

    public String buildUserPath(final RestfulMock mock) {

        if (!SmockinUserRoleEnum.SYS_ADMIN.equals(mock.getCreatedBy().getRole())) {
            return File.separator + mock.getCreatedBy().getCtxPath() + mock.getPath();
        }

        return mock.getPath();
    }

}
