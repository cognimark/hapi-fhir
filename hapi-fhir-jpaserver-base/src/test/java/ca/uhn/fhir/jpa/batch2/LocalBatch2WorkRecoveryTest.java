package ca.uhn.fhir.jpa.batch2;

import ca.uhn.fhir.batch2.api.IJobPersistence;
import ca.uhn.fhir.batch2.coordinator.JobDefinitionRegistry;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.batch2.model.StatusEnum;
import ca.uhn.fhir.batch2.model.WorkChunkStatusEnum;
import ca.uhn.fhir.broker.api.IBrokerClient;
import ca.uhn.fhir.broker.impl.LinkedBlockingBrokerClient;
import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.jpa.dao.data.IBatch2WorkChunkRepository;
import ca.uhn.fhir.jpa.dao.tx.NonTransactionalHapiTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalBatch2WorkRecoveryTest {

	private static final Date CONTEXT_START = new Date(100_000);
	@Mock private IJobPersistence myPersistence;
	@Mock private IBatch2WorkChunkRepository myChunks;
	@Mock private JobDefinitionRegistry myDefinitions;
	private LocalBatch2WorkRecovery myRecovery;
	private final IBrokerClient myBroker = mock(LinkedBlockingBrokerClient.class);

	@BeforeEach
	void setUp() {
		myRecovery = new LocalBatch2WorkRecovery(
				myPersistence, myChunks, new NonTransactionalHapiTransactionService(), myDefinitions);
	}

	@Test
	void rejectsBrokersThatOwnTheirOwnRedelivery() {
		assertThatThrownBy(() -> myRecovery.recoverBeforeScheduling(mock(IBrokerClient.class), CONTEXT_START))
				.isInstanceOf(ConfigurationException.class);
		verifyNoInteractions(myPersistence, myChunks, myDefinitions);
	}

	@Test
	void onlyRestoresTheCurrentStepWithoutChangingJobParametersOrPayloads() {
		JobInstance job = job("old-job");
		when(myPersistence.fetchInstances(100, 0, LocalBatch2WorkRecovery.ACTIVE_JOBS)).thenReturn(List.of(job));
		when(myChunks.restoreLocalDispatchability("old-job", "current-step", CONTEXT_START,
				LocalBatch2WorkRecovery.ACTIVE_JOBS, LocalBatch2WorkRecovery.LOST_NOTIFICATIONS,
				WorkChunkStatusEnum.READY)).thenReturn(123);

		assertThat(myRecovery.recoverBeforeScheduling(myBroker, CONTEXT_START)).isEqualTo(123);
		verify(myDefinitions).getJobDefinitionOrThrowException(job);
		verify(myChunks).restoreLocalDispatchability("old-job", "current-step", CONTEXT_START,
				LocalBatch2WorkRecovery.ACTIVE_JOBS, LocalBatch2WorkRecovery.LOST_NOTIFICATIONS,
				WorkChunkStatusEnum.READY);
		assertThat(LocalBatch2WorkRecovery.ACTIVE_JOBS)
				.containsExactlyInAnyOrder(StatusEnum.QUEUED, StatusEnum.IN_PROGRESS, StatusEnum.ERRORED);
		assertThat(LocalBatch2WorkRecovery.LOST_NOTIFICATIONS).containsExactlyInAnyOrder(
				WorkChunkStatusEnum.QUEUED, WorkChunkStatusEnum.IN_PROGRESS, WorkChunkStatusEnum.ERRORED);
	}

	@Test
	void doesNotRecoverCancelledOrCurrentBootWork() {
		JobInstance cancelled = job("cancelled");
		cancelled.setCancelled(true);
		JobInstance newJob = job("new");
		newJob.setCreateTime(CONTEXT_START);
		when(myPersistence.fetchInstances(100, 0, LocalBatch2WorkRecovery.ACTIVE_JOBS))
				.thenReturn(List.of(cancelled, newJob));

		assertThat(myRecovery.recoverBeforeScheduling(myBroker, CONTEXT_START)).isZero();
		verifyNoInteractions(myChunks, myDefinitions);
	}

	@Test
	void pagesJobsWithoutAWholeDatabaseSnapshotInMemory() {
		List<JobInstance> first = IntStream.range(0, 100).mapToObj(i -> job("job-" + i)).toList();
		when(myPersistence.fetchInstances(100, 0, LocalBatch2WorkRecovery.ACTIVE_JOBS)).thenReturn(first);
		when(myPersistence.fetchInstances(100, 1, LocalBatch2WorkRecovery.ACTIVE_JOBS)).thenReturn(List.of(job("last")));
		when(myChunks.restoreLocalDispatchability(anyString(), anyString(), any(), anySet(), anySet(), any()))
				.thenReturn(1);

		assertThat(myRecovery.recoverBeforeScheduling(myBroker, CONTEXT_START)).isEqualTo(101);
		verify(myChunks, times(101)).restoreLocalDispatchability(anyString(), eq("current-step"),
				eq(CONTEXT_START), anySet(), anySet(), eq(WorkChunkStatusEnum.READY));
	}

	private static JobInstance job(String id) {
		JobInstance job = new JobInstance();
		job.setInstanceId(id);
		job.setJobDefinitionId("test-job");
		job.setJobDefinitionVersion(1);
		job.setStatus(StatusEnum.IN_PROGRESS);
		job.setCreateTime(new Date(1));
		job.setCurrentGatedStepId("current-step");
		return job;
	}
}
