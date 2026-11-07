package ca.uhn.fhir.jpa.batch2;

import ca.uhn.fhir.batch2.model.WorkChunk;
import ca.uhn.fhir.batch2.model.WorkChunkStatusEnum;
import ca.uhn.fhir.jpa.dao.data.IBatch2AttachmentChunkRepository;
import ca.uhn.fhir.jpa.dao.data.IBatch2AttachmentRepository;
import ca.uhn.fhir.jpa.dao.data.IBatch2JobInstanceRepository;
import ca.uhn.fhir.jpa.dao.data.IBatch2WorkChunkRepository;
import ca.uhn.fhir.jpa.dao.tx.NonTransactionalHapiTransactionService;
import ca.uhn.fhir.jpa.entity.Batch2WorkChunkEntity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkChunkPagingTest {

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void traversalSurvivesDeletionOfEarlierRowsAndSequenceTies(boolean withData) {
		int pageSize = withData ? 100 : 1000;
		List<WorkChunk> rows = chunks(pageSize * 2 + 5);
		List<String> expected = rows.stream().map(WorkChunk::getId).toList();
		AtomicInteger queries = new AtomicInteger();
		Iterator<WorkChunk> iterator = persistence(rows, queries).fetchAllWorkChunksIterator("job", withData);
		List<String> actual = new ArrayList<>();
		for (int i = 0; i < pageSize; i++) {
			assertThat(iterator.hasNext()).isTrue();
			assertThat(iterator.hasNext()).isTrue();
			WorkChunk chunk = iterator.next();
			actual.add(chunk.getId());
			assertThat(chunk.getData()).isEqualTo(withData ? "payload-" + chunk.getId() : null);
		}
		// A consumer may remove completed rows while another pass traverses the job.
		rows.subList(0, pageSize).clear();
		iterator.forEachRemaining(chunk -> actual.add(chunk.getId()));
		assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
		assertThat(queries.get()).isEqualTo(3);
		assertThat(iterator.hasNext()).isFalse();
		assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
		assertThat(queries.get()).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void fullLastPageAndEmptyJobFinishWithoutRepeatedQueries(boolean withData) {
		int pageSize = withData ? 100 : 1000;
		AtomicInteger queries = new AtomicInteger();
		Iterator<WorkChunk> iterator = persistence(chunks(pageSize), queries)
			.fetchAllWorkChunksIterator("job", withData);
		List<WorkChunk> result = new ArrayList<>();
		iterator.forEachRemaining(result::add);
		assertThat(result).hasSize(pageSize);
		assertThat(queries.get()).isEqualTo(2);
		assertThat(iterator.hasNext()).isFalse();
		assertThat(iterator.hasNext()).isFalse();
		assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
		assertThat(queries.get()).isEqualTo(2);

		queries.set(0);
		iterator = persistence(List.of(), queries).fetchAllWorkChunksIterator("job", withData);
		assertThat(iterator.hasNext()).isFalse();
		assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
		assertThat(queries.get()).isEqualTo(1);
	}

	private static List<WorkChunk> chunks(int count) {
		List<WorkChunk> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			WorkChunk chunk = new WorkChunk();
			chunk.setId(String.format("chunk-%05d", i));
			// Repeated sequence numbers straddle both payload and metadata page boundaries.
			chunk.setSequence(i / 7);
			chunk.setInstanceId("job");
			chunk.setJobDefinitionId("definition");
			chunk.setJobDefinitionVersion(1);
			chunk.setTargetStepId("step");
			chunk.setStatus(WorkChunkStatusEnum.COMPLETED);
			chunk.setRecordsProcessed(i);
			chunk.setData("payload-" + chunk.getId());
			rows.add(chunk);
		}
		return rows;
	}

	private static JpaJobPersistenceImpl persistence(List<WorkChunk> rows, AtomicInteger queries) {
		IBatch2WorkChunkRepository repository = mock(IBatch2WorkChunkRepository.class, call -> {
			String method = call.getMethod().getName();
			if (!method.startsWith("fetchChunks")) {
				throw new AssertionError("Unexpected repository call: " + method);
			}
			queries.incrementAndGet();
			Pageable page = call.getArgument(0);
			String instanceId = call.getArgument(1);
			boolean after = method.endsWith("After");
			int sequence = after ? call.getArgument(2) : 0;
			String id = after ? call.getArgument(3) : null;
			return rows.stream()
				.filter(row -> row.getInstanceId().equals(instanceId))
				.filter(row -> !after || row.getSequence() > sequence
					|| row.getSequence() == sequence && row.getId().compareTo(id) > 0)
				.sorted(Comparator.comparingInt(WorkChunk::getSequence).thenComparing(WorkChunk::getId))
				.skip(page.getOffset()).limit(page.getPageSize())
				.map(Batch2WorkChunkEntity::fromWorkChunk)
				.peek(entity -> {
					if (method.contains("NoData")) {
						entity.setSerializedData(null);
					}
				})
				.toList();
		});
		return new JpaJobPersistenceImpl(mock(IBatch2AttachmentRepository.class),
			mock(IBatch2JobInstanceRepository.class), repository, null,
			new NonTransactionalHapiTransactionService(), null, null,
			mock(IBatch2AttachmentChunkRepository.class));
	}
}
