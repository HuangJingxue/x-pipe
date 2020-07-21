package com.ctrip.xpipe.redis.console.healthcheck.actions.redisstats.conflic;

import com.ctrip.xpipe.api.foundation.FoundationService;
import com.ctrip.xpipe.cluster.ClusterType;
import com.ctrip.xpipe.redis.console.AbstractConsoleTest;
import com.ctrip.xpipe.redis.console.healthcheck.*;
import com.ctrip.xpipe.simpleserver.Server;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class ConflictCheckActionTest extends AbstractConsoleTest {

    private RedisHealthCheckInstance instance;

    private Server redis;

    ConflictCheckAction action;

    private int redisDelay = 0;

    private AtomicInteger redisCallCnt = new AtomicInteger(0);

    private long typeConflict = 0L;
    private long nonTypeConflict = 0L;
    private long modifyConflict = 0L;
    private long mergeConflict = 0L;

    private String TEMP_STATS_RESP = "crdt_type_conflict:%d\r\n" +
            "crdt_non_type_conflict:%d\r\n" +
            "crdt_modify_conflict:%d\r\n" +
            "crdt_merge_conflict:%d\r\n";

    @Before
    public void setupConflictCheckActionTest() throws Exception {
        redis = startServer(randomPort(), new Function<String, String>() {
            @Override
            public String apply(String s) {
                redisCallCnt.incrementAndGet();
                if (redisDelay > 0) sleep(redisDelay);

                if (s.trim().toLowerCase().startsWith("crdt.info stats")) {
                    return mockConflictResponse();
                } else {
                    return "+OK\r\n";
                }
            }
        });

        instance = newRandomRedisHealthCheckInstance(FoundationService.DEFAULT.getDataCenter(), ClusterType.BI_DIRECTION, redis.getPort());
        action = new ConflictCheckAction(scheduled, instance, executors);
    }

    @After
    public void afterConflictCheckActionTest() throws Exception {
        if (null != redis) redis.stop();
    }

    @Test
    public void testDoTask() throws Exception {
        AtomicInteger callCnt = new AtomicInteger(0);
        AtomicReference<ActionContext> contextRef = new AtomicReference();
        typeConflict = Math.abs(randomInt());
        nonTypeConflict = Math.abs(randomInt());
        mergeConflict = Math.abs(randomInt());
        modifyConflict = Math.abs(randomInt());

        action.addListener(new HealthCheckActionListener() {
            @Override
            public void onAction(ActionContext actionContext) {
                callCnt.incrementAndGet();
                contextRef.set(actionContext);
            }

            @Override
            public boolean worksfor(ActionContext t) {
                return true;
            }

            @Override
            public void stopWatch(HealthCheckAction action) {

            }
        });
        AbstractHealthCheckAction.ScheduledHealthCheckTask task = action.new ScheduledHealthCheckTask();
        task.run();

        waitConditionUntilTimeOut(() -> callCnt.get() == 1, 1000);

        CrdtConflictCheckContext context = (CrdtConflictCheckContext) contextRef.get();
        Assert.assertEquals(1, callCnt.get());
        Assert.assertEquals(typeConflict, context.getResult().getTypeConflict());
        Assert.assertEquals(nonTypeConflict, context.getResult().getNonTypeConflict());
        Assert.assertEquals(mergeConflict, context.getResult().getMergeConflict());
        Assert.assertEquals(modifyConflict, context.getResult().getModifyConflict());

        // test redis time out
        redisDelay = 510;
        task.run();
        sleep(redisDelay + 200);
        Assert.assertEquals(1, callCnt.get());
    }

    @Test
    public void testDoActionTooQuickly() {
        redisDelay = 200;
        AbstractHealthCheckAction.ScheduledHealthCheckTask task = action.new ScheduledHealthCheckTask();
        task.run();
        sleep(10);
        task.run();

        sleep(200);
        Assert.assertEquals(1, redisCallCnt.get());
    }

    private String mockConflictResponse() {
        String content = String.format(TEMP_STATS_RESP, typeConflict, nonTypeConflict, modifyConflict, mergeConflict);
        return String.format("$%d\r\n%s", content.length(), content);
    }



}
