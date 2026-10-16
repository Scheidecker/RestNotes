package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.note.AddTagRequest;
import com.scheideckerantunes.notes.note.Note;
import com.scheideckerantunes.notes.note.NoteOperationException;
import com.scheideckerantunes.notes.note.NoteRequestBodyAdvice;
import com.scheideckerantunes.notes.note.TagNames;
import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure tag naming and aggregate mutation rules require no database.
 *
 * @author Carlos Scheidecker
 */
class TagRulesTest {
    @Test
    void normalizesCaseWhitespaceAndHumanReadableNames() {
        assertThat(TagNames.require(" Design Review ")).isEqualTo("design review");
        for (String name : List.of("c++", "c#", "café", "api.v1", "release-plan", "🎯")) {
            assertThat(TagNames.valid(TagNames.normalize(name))).isTrue();
        }
        assertThat(new AddTagRequest(" DESIGN ").name()).isEqualTo("design");
    }

    @Test
    void rejectsUnsafeAndOversizedNamesAfterNormalization() {
        for (String name : List.of("", " ", ".", "..", "a/b", "a\\b", "a;b", "a%b",
                "a\nb", "a\u0000b", "a\u2028b", "a\u202Eb", "n".repeat(51), "İ".repeat(26))) {
            assertThatThrownBy(() -> TagNames.require(name))
                    .isInstanceOf(NoteOperationException.class).hasMessage("INVALID_TAG_NAME");
        }
        assertThat(TagNames.require("n".repeat(50)).length()).isEqualTo(50);
    }

    @Test
    void validatesRequestsAndRedactsTheirRepresentations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new AddTagRequest(null))).isNotEmpty();
            assertThat(validator.validate(new AddTagRequest("a/b"))).isNotEmpty();
            assertThat(validator.validate(new AddTagRequest("İ".repeat(26)))).isNotEmpty();
            assertThat(validator.validate(new AddTagRequest(" Valid Label "))).isEmpty();
        }
        String label = UUID.randomUUID().toString();
        assertThat(new AddTagRequest(label).toString().contains(label)).isFalse();
        assertThat(new NoteRequestBodyAdvice().supports(null, AddTagRequest.class, null)).isTrue();
    }

    @Test
    void tagChangesPreserveContentCreatorAndPinning() {
        UUID creator = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        Note note = new Note(UUID.randomUUID(), UUID.randomUUID(), "Title", "Markdown", true, creator);
        note.tagsChanged(editor);
        assertThat(note.getCreatedBy()).isEqualTo(creator);
        assertThat(note.getUpdatedBy()).isEqualTo(editor);
        assertThat(note.getTitle()).isEqualTo("Title");
        assertThat(note.getContent().equals("Markdown")).isTrue();
        assertThat(note.isPinned()).isTrue();
        assertThat(note.getUpdatedAt()).isNotNull();
    }
}
