package ca.uhn.fhir.batch2.maintenance;

import ca.uhn.fhir.batch2.api.IJobPersistence;
import ca.uhn.fhir.batch2.api.IReductionStepExecutorService;
import ca.uhn.fhir.batch2.channel.BatchJobSender;
import ca.uhn.fhir.batch2.coordinator.JobDefinitionRegistry;
import ca.uhn.fhir.batch2.model.WorkChunkMetadata;
import ca.uhn.fhir.batch2.model.WorkChunkStatusEnum;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadyChunkPagingTest {

	@Test
	void dispatchDoesNotSkipReadyChunksWhenEarlierPagesLeaveReadyState() {
		IJobPersistence persistence = mock(IJobPersistence.class);
		List<WorkChunkMetadata> ready = new ArrayList<>();
		for (int i = 0; i < 20005; i++) {
			ready.add(new WorkChunkMetadata().setId(String.format("chunk-%05d", i))
				.setStatus(WorkChunkStatusEnum.READY));
		}
		when(persistence.fetchAllWorkChunkMetadataForJobInStates(
			any(Pageable.class), eq("job"), eq(Set.of(WorkChunkStatusEnum.READY))))
			.thenAnswer(call -> {
				Pageable page = call.getArgument(0);
				int from = Math.min((int) page.getOffset(), ready.size());
				int to = Math.min(from + page.getPageSize(), ready.size());
				return new PageImpl<>(new ArrayList<>(ready.subList(from, to)), page, ready.size());
			});
		ActiveJobInstanceProcessor processor = new ActiveJobInstanceProcessor(persistence,
			mock(BatchJobSender.class), "job", mock(IReductionStepExecutorService.class),
			mock(JobDefinitionRegistry.class), mock(IInterceptorService.class));
		var iterator = processor.getReadyChunks();
		List<String> dispatched = new ArrayList<>();
		while (iterator.hasNext()) {
			WorkChunkMetadata chunk = iterator.next();
			assertThat(ready.remove(chunk)).isTrue();
			dispatched.add(chunk.getId());
		}
		assertThat(dispatched).hasSize(20005).doesNotHaveDuplicates();
		assertThat(ready).isEmpty();
	}
}
