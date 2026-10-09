/*-
 * #%L
 * HAPI FHIR JPA Server
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
import ca.uhn.fhir.jpa.dao.tx.IHapiTransactionService;
import org.springframework.transaction.annotation.Propagation;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Restore dispatchability after the sole owner of a local, non-durable broker
 * exits. Call once during startup, after definitions are registered but before
 * maintenance starts. The deployment must exclude any other database owner.
 *
 * Completed checkpoints, chunk data, retry counts and job parameters are never
 * reset. Native maintenance handles READY -> QUEUED and bounded dispatch. This
 * avoids blocking application startup while filling the 1,000-message queue.
 */
public class LocalBatch2WorkRecovery {

	static final Set<StatusEnum> ACTIVE_JOBS = Set.of(StatusEnum.QUEUED, StatusEnum.IN_PROGRESS, StatusEnum.ERRORED);
	static final Set<WorkChunkStatusEnum> LOST_NOTIFICATIONS =
			Set.of(WorkChunkStatusEnum.QUEUED, WorkChunkStatusEnum.IN_PROGRESS, WorkChunkStatusEnum.ERRORED);
	static final int PAGE_SIZE = 100;

	private final IJobPersistence myPersistence;
	private final IBatch2WorkChunkRepository myChunks;
	private final IHapiTransactionService myTransactions;
	private final JobDefinitionRegistry myDefinitions;

	public LocalBatch2WorkRecovery(
			IJobPersistence thePersistence,
			IBatch2WorkChunkRepository theChunks,
			IHapiTransactionService theTransactions,
			JobDefinitionRegistry theDefinitions) {
		myPersistence = thePersistence;
		myChunks = theChunks;
		myTransactions = theTransactions;
		myDefinitions = theDefinitions;
	}

	public int recoverBeforeScheduling(IBrokerClient theBroker, Date theContextStart) {
		if (!(theBroker instanceof LinkedBlockingBrokerClient)) {
			throw new ConfigurationException("Single-node local queue recovery requires LinkedBlockingBrokerClient");
		}
		int recovered = 0;
		for (int page = 0; ; page++) {
			List<JobInstance> jobs = myPersistence.fetchInstances(PAGE_SIZE, page, ACTIVE_JOBS);
			for (JobInstance job : jobs) {
				if (job.isPendingCancellationRequest() || !job.getCreateTime().before(theContextStart)) {
					continue;
				}
				myDefinitions.getJobDefinitionOrThrowException(job);
				recovered += myTransactions
						.withSystemRequestOnDefaultPartition()
						.withPropagation(Propagation.REQUIRES_NEW)
						.execute(() -> myChunks.restoreLocalDispatchability(
								job.getInstanceId(),
								job.getCurrentGatedStepId(),
								theContextStart,
								ACTIVE_JOBS,
								LOST_NOTIFICATIONS,
								WorkChunkStatusEnum.READY));
			}
			// Recovery changes only chunk states, so the job-status page set stays
			// stable until native maintenance starts after this method returns.
			if (jobs.size() < PAGE_SIZE) {
				return recovered;
			}
		}
	}
}
