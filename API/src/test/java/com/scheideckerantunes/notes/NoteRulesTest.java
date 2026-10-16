package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.note.CreateNoteRequest;
import com.scheideckerantunes.notes.note.Note;
import com.scheideckerantunes.notes.note.NoteEtags;
import com.scheideckerantunes.notes.note.NoteLimits;
import com.scheideckerantunes.notes.note.NoteOperationException;
import com.scheideckerantunes.notes.note.NoteRequestBodyAdvice;
import com.scheideckerantunes.notes.note.PatchNoteRequest;
import com.scheideckerantunes.notes.note.ReplaceNoteRequest;
import com.scheideckerantunes.notes.team.TeamRole;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure note rules do not start Spring or Docker and never print content.
 *
 * @author Carlos Scheidecker
 */
class NoteRulesTest {
    @Test
    void contentLimitsUseUtf8BytesAndPutRequiresPinning() {
        assertThat(NoteLimits.contentFits("a".repeat(NoteLimits.CONTENT_BYTES))).isTrue();
        assertThat(NoteLimits.contentFits("é".repeat(NoteLimits.CONTENT_BYTES / 2))).isTrue();
        assertThat(NoteLimits.contentFits("é".repeat(NoteLimits.CONTENT_BYTES / 2 + 1))).isFalse();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ReplaceNoteRequest("Title", "", null))).isNotEmpty();
            assertThat(validator.validate(new CreateNoteRequest(" ", "", false))).isNotEmpty();
            assertThat(validator.validate(new CreateNoteRequest("Title", "", null))).isEmpty();
        }
    }

    @Test
    void patchDistinguishesOmittedNullAndFalse() {
        var mapper = JsonMapper.builder().build();
        var omitted = mapper.readValue("{}", PatchNoteRequest.class);
        assertThat(omitted.contentPresent()).isFalse();
        var explicit = mapper.readValue("{\"content\":null,\"pinned\":false}", PatchNoteRequest.class);
        assertThat(explicit.contentPresent()).isTrue();
        assertThat(explicit.getContent()).isNull();
        assertThat(explicit.pinnedPresent()).isTrue();
        assertThat(explicit.getPinned()).isFalse();
    }

    @Test
    void realChangesPreserveCreatorAndNoOpsPreserveUpdater() {
        UUID creator = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        Note note = new Note(UUID.randomUUID(), UUID.randomUUID(), "Title", "Body", creator);
        assertThat(note.applyChanges("Title", "Body", false, editor)).isFalse();
        assertThat(note.getUpdatedBy()).isEqualTo(creator);
        assertThat(note.applyChanges("Title", "Body", true, editor)).isTrue();
        assertThat(note.getCreatedBy()).isEqualTo(creator);
        assertThat(note.getUpdatedBy()).isEqualTo(editor);
        assertThat(note.archive(Instant.now(), editor)).isTrue();
        assertThat(note.archive(Instant.now(), creator)).isFalse();
        assertThat(note.getUpdatedBy()).isEqualTo(editor);
    }

    @Test
    void conditionalTagsIncludeBothVersionsAndCurrentCapability() {
        UUID note = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String expected = NoteEtags.value(note, 2, book, 3, actor, TeamRole.EDITOR);
        NoteEtags.require(expected, expected);
        rejected(expected, null, "MISSING_IF_MATCH");
        rejected(expected, "*", "INVALID_IF_MATCH");
        rejected(expected, "W/" + expected, "INVALID_IF_MATCH");
        rejected(expected, expected + "," + expected, "INVALID_IF_MATCH");
        rejected(expected, NoteEtags.value(note, 1, book, 3, actor, TeamRole.EDITOR), "STALE_IF_MATCH");
        rejected(expected, NoteEtags.value(note, 2, book, 4, actor, TeamRole.EDITOR), "STALE_IF_MATCH");
        rejected(expected, NoteEtags.value(note, 2, book, 3, actor, TeamRole.VIEWER), "STALE_IF_MATCH");
    }

    @Test
    void requestRepresentationsRedactTitleAndMarkdown() {
        String content = UUID.randomUUID().toString();
        assertThat(new CreateNoteRequest("Private title", content, false).toString().contains(content)).isFalse();
        assertThat(new ReplaceNoteRequest("Private title", content, true).toString().contains(content)).isFalse();
        PatchNoteRequest patch = new PatchNoteRequest();
        patch.setContent(content);
        assertThat(patch.toString().contains(content)).isFalse();
    }

    @Test
    void bodyLimitWorksWithoutContentLengthAndRejectsOversizedDeclarations() throws Exception {
        var advice = new NoteRequestBodyAdvice();
        HttpHeaders headers = new HttpHeaders();
        HttpInputMessage input = new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(new byte[NoteLimits.BODY_BYTES + 1]);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
        var bounded = advice.beforeBodyRead(input, null, CreateNoteRequest.class, null);
        assertThatThrownBy(() -> bounded.getBody().readAllBytes())
                .isInstanceOf(NoteRequestBodyAdvice.BodyLimitException.class);
        headers.setContentLength(NoteLimits.BODY_BYTES + 1L);
        assertThatThrownBy(() -> advice.beforeBodyRead(input, null, CreateNoteRequest.class, null))
                .isInstanceOf(NoteOperationException.class).hasMessage("BODY_TOO_LARGE");
    }

    private void rejected(String expected, String supplied, String code) {
        assertThatThrownBy(() -> NoteEtags.require(expected, supplied))
                .isInstanceOf(NoteOperationException.class).hasMessage(code);
    }
}
