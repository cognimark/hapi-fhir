package ca.uhn.fhir.rest.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// Created by GPT-6
class SearchTotalModeEnumTest {

	@ParameterizedTest
	@CsvSource({"none, NONE", "estimate, ESTIMATED", "accurate, ACCURATE"})
	void standardSearchTotalCodes(String theCode, SearchTotalModeEnum theMode) {
		assertThat(SearchTotalModeEnum.fromCode(theCode)).isEqualTo(theMode);
		assertThat(theMode.getCode()).isEqualTo(theCode);
	}
}
