package ca.uhn.fhir.jpa.search.builder;

import ca.uhn.fhir.jpa.model.dao.JpaPid;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class SearchQueryExecutorsTest {

	@Test
	void sqlMembershipRetainsNativeCandidateOrderBeforePaging() {
		JpaPid first = JpaPid.fromId(3L, 1001);
		JpaPid second = JpaPid.fromId(8L, 1001);
		ISearchQueryExecutor sql = spy(SearchQueryExecutors.from(List.of(second, first, second)));
		try (ISearchQueryExecutor ordered = SearchQueryExecutors.inCandidateOrder(
				JpaPid.fromLongList(List.of(1L, 3L, 8L, 12L)), sql)) {
			assertThat(ordered.next()).isSameAs(first);
			assertThat(ordered.next()).isSameAs(second);
			assertThat(ordered.hasNext()).isFalse();
		}
		verify(sql).close();
	}

	@Test
	void pageOffsetCrossesFilteredChunksAndClosesUnusedQueries() {
		ISearchQueryExecutor first = spy(SearchQueryExecutors.from(JpaPid.fromLongList(List.of(1L, 3L))));
		ISearchQueryExecutor empty = spy(SearchQueryExecutors.from(List.of()));
		ISearchQueryExecutor second = spy(SearchQueryExecutors.from(JpaPid.fromLongList(List.of(8L, 12L, 15L))));
		ISearchQueryExecutor unused = spy(SearchQueryExecutors.from(JpaPid.fromLongList(List.of(22L))));
		try (ISearchQueryExecutor window = SearchQueryExecutors.window(List.of(first, empty, second, unused), 3, 2)) {
			assertThat(drain(window)).containsExactly(12L, 15L);
			assertThat(window.hasNext()).isFalse();
		}
		for (ISearchQueryExecutor query : List.of(first, empty, second, unused)) {
			verify(query).close();
		}
	}

	@Test
	void pageBeyondAllMatchesIsEmptyAndUnboundedReadRetainsAllMatches() {
		try (ISearchQueryExecutor window = SearchQueryExecutors.window(
				List.of(SearchQueryExecutors.from(JpaPid.fromLongList(List.of(1L, 2L)))), 3, 2)) {
			assertThat(drain(window)).isEmpty();
		}
		try (ISearchQueryExecutor window = SearchQueryExecutors.window(List.of(
				SearchQueryExecutors.from(JpaPid.fromLongList(List.of(1L))),
				SearchQueryExecutors.from(JpaPid.fromLongList(List.of(2L, 3L)))), null, null)) {
			assertThat(drain(window)).containsExactly(1L, 2L, 3L);
		}
	}

	@Test
	public void adaptFromLongArrayYieldsAllValues() {
		List<JpaPid> listWithValues = JpaPid.fromLongList(Arrays.asList(1L,2L,3L,4L,5L));

		ISearchQueryExecutor queryExecutor = SearchQueryExecutors.from(listWithValues);

		assertThat(drain(queryExecutor)).containsExactly(1L, 2L, 3L, 4L, 5L);
	}

	@Test
	public void limitedCountDropsTrailingTest() {
		// given
		List<JpaPid> vals = JpaPid.fromLongList(Arrays.asList(1L,2L,3L,4L,5L));
		ISearchQueryExecutor target = SearchQueryExecutors.from(vals);

		ISearchQueryExecutor queryExecutor = SearchQueryExecutors.limited(target, 3);

		assertThat(drain(queryExecutor)).containsExactly(1L, 2L, 3L);
	}

	@Test
	public void limitedCountExhaustsBeforeLimitOkTest() {
		// given
		List<JpaPid> vals = JpaPid.fromLongList(Arrays.asList(1L,2L,3L));
		ISearchQueryExecutor target = SearchQueryExecutors.from(vals);

		ISearchQueryExecutor queryExecutor = SearchQueryExecutors.limited(target, 5);

		assertThat(drain(queryExecutor)).containsExactly(1L, 2L, 3L);
	}


	private List<Long> drain(ISearchQueryExecutor theQueryExecutor) {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(theQueryExecutor, 0), false)
			.map(JpaPid::getId)
			.collect(Collectors.toList());
	}


}
