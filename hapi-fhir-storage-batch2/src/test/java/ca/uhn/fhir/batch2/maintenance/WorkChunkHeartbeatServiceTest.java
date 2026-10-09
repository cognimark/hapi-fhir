/*-
 * #%L
 * HAPI FHIR JPA Server - Batch2 Task Processor
 * %%
 * Copyright (C) 2014 - 2026 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.batch2.maintenance;

import ca.uhn.fhir.batch2.api.IWorkChunkPersistence;
import ca.uhn.fhir.jpa.model.sched.HapiJob;
import ca.uhn.fhir.jpa.model.sched.ScheduledJobDefinition;
import ca.uhn.fhir.jpa.sched.AutowiringSpringBeanJobFactory;
import ca.uhn.fhir.jpa.sched.BaseSchedulerServiceImpl;
import ca.uhn.fhir.jpa.sched.HapiSchedulerServiceImpl;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkChunkHeartbeatServiceTest {

	private AnnotationConfigApplicationContext myContext;
	private HapiSchedulerServiceImpl myScheduler;
	private WorkChunkHeartbeatService myService;
	private IWorkChunkPersistence myPersistence;

	@BeforeEach
	void beforeEach() {
		myPersistence = mock(IWorkChunkPersistence.class);
		myContext = new AnnotationConfigApplicationContext();
		myContext.registerBean(IWorkChunkPersistence.class, () -> myPersistence);
		myContext.registerBean(AutowiringSpringBeanJobFactory.class);
		myContext.registerBean(HapiSchedulerServiceImpl.class, () -> {
			HapiSchedulerServiceImpl scheduler = new HapiSchedulerServiceImpl();
			scheduler.setDefaultGroup("HAPI");
			return scheduler;
		});
		myContext.refresh();
		myScheduler = myContext.getBean(HapiSchedulerServiceImpl.class);
		myService = new WorkChunkHeartbeatService(myScheduler);
	}

	@AfterEach
	void afterEach() {
		myContext.close();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"HAPI", "custom-group"})
	void completedChunkRemovesItsActualScheduledJob(String theDefaultGroup) throws Exception {
		myScheduler.setDefaultGroup(theDefaultGroup);
		try (var ignored = myService.scheduleHeartbeatJob("instance", "completed")) {
			assertThat(myScheduler.getLocalJobKeysForUnitTest())
				.containsExactly(new JobKey("BATCH2-HEARTBEAT-instance-completed", theDefaultGroup));
		}
		assertThat(myScheduler.getLocalJobKeysForUnitTest()).isEmpty();
	}

	@Test
	void failedChunkAlsoRemovesItsHeartbeat() throws Exception {
		assertThatThrownBy(() -> {
			try (var ignored = myService.scheduleHeartbeatJob("instance", "failed")) {
				throw new IllegalStateException("synthetic worker failure");
			}
		}).isInstanceOf(IllegalStateException.class);
		assertThat(myScheduler.getLocalJobKeysForUnitTest()).isEmpty();
	}

	@Test
	void repeatedCloseDoesNotCancelAnotherChunk() throws Exception {
		var completed = myService.scheduleHeartbeatJob("instance", "completed");
		try (var ignored = myService.scheduleHeartbeatJob("instance", "still-working")) {
			completed.close();
			completed.close();
			assertThat(myScheduler.getLocalJobKeysForUnitTest())
				.containsExactly(new JobKey("BATCH2-HEARTBEAT-instance-still-working", "HAPI"));
		}
		assertThat(myScheduler.getLocalJobKeysForUnitTest()).isEmpty();
	}

	@Test
	void noChunkCreatesNoHeartbeat() throws Exception {
		var handle = myService.scheduleHeartbeatJob("instance", null);
		handle.close();
		handle.close();
		assertThat(myScheduler.getLocalJobKeysForUnitTest()).isEmpty();
		verifyNoInteractions(myPersistence);
	}

	@Test
	void heartbeatRunsWhileActiveButStopsAfterClose() throws Exception {
		AtomicInteger heartbeats = new AtomicInteger();
		doAnswer(invocation -> {
			heartbeats.incrementAndGet();
			return null;
		}).when(myPersistence).onWorkChunkHeartbeat(anyString());
		myService.setAckTimeout(Duration.ofMillis(1500));
		try (var ignored = myService.scheduleHeartbeatJob("instance", "active")) {
			await().atMost(Duration.ofSeconds(10)).until(() -> heartbeats.get() >= 2);
		}
		assertThat(myScheduler.getLocalJobKeysForUnitTest()).isEmpty();
		// Allow an already-fired invocation to drain, then require a stable count
		// for longer than two heartbeat intervals with the scheduler still running.
		AtomicInteger lastObserved = new AtomicInteger(heartbeats.get());
		await().atMost(Duration.ofSeconds(5)).during(Duration.ofMillis(1200))
			.until(() -> {
				int count = heartbeats.get();
				return lastObserved.getAndSet(count) == count;
			});
	}

	@Test
	void registrationDetailsAreDebugOnly() {
		Logger logger = (Logger) LoggerFactory.getLogger(BaseSchedulerServiceImpl.class);
		Level originalLevel = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);
		try {
			ScheduledJobDefinition interval = definition("interval");
			ScheduledJobDefinition cron = definition("cron");
			myScheduler.scheduleLocalJob(1000, interval);
			myScheduler.scheduleLocalJob("0/10 * * * * ?", cron);
			assertThat(appender.list).filteredOn(event -> event.getMessage().startsWith("Scheduling local job")
					|| event.getMessage().startsWith("Scheduling {} job"))
				.hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
		} finally {
			logger.setLevel(originalLevel);
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private static ScheduledJobDefinition definition(String theId) {
		ScheduledJobDefinition definition = new ScheduledJobDefinition();
		definition.setId(theId);
		definition.setJobClass(NoOpJob.class);
		return definition;
	}

	public static class NoOpJob implements HapiJob {
		@Override
		public void execute(JobExecutionContext theContext) {}
	}
}
