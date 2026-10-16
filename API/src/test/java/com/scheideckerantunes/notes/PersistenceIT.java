package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.activity.ActivityLogRepository;
import com.scheideckerantunes.notes.comment.NoteComment;
import com.scheideckerantunes.notes.comment.NoteCommentRepository;
import com.scheideckerantunes.notes.history.NoteHistoryRepository;
import com.scheideckerantunes.notes.note.Note;
import com.scheideckerantunes.notes.note.NoteRepository;
import com.scheideckerantunes.notes.note.NoteTag;
import com.scheideckerantunes.notes.note.NoteTagRepository;
import com.scheideckerantunes.notes.note.Tag;
import com.scheideckerantunes.notes.note.TagRepository;
import com.scheideckerantunes.notes.notebook.NotebookRepository;
import com.scheideckerantunes.notes.team.TeamMemberId;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository and constraint tests use real migrations and roll back their own edits.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@ActiveProfiles("test")
@Transactional
class PersistenceIT extends MySqlIntegrationSupport {

    private static final UUID ENG_USER = id(1, 1);
    private static final UUID MKT_USER = id(1, 2);
    private static final UUID ENG_TEAM = id(2, 1);
    private static final UUID MKT_TEAM = id(2, 2);
    private static final UUID ENG_BOOK = id(3, 1);
    private static final UUID ENG_NOTE = id(4, 1);
    private static final UUID MKT_NOTE = id(4, 2);

    @Autowired
    private UserAccountRepository users;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private TeamMemberRepository members;
    @Autowired
    private NotebookRepository notebooks;
    @Autowired
    private NoteRepository notes;
    @Autowired
    private TagRepository tags;
    @Autowired
    private NoteTagRepository noteTags;
    @Autowired
    private NoteCommentRepository comments;
    @Autowired
    private NoteHistoryRepository history;
    @Autowired
    private ActivityLogRepository activity;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsExactlyTheReviewedSeedGraphWithEncodedPasswords() {
        assertThat(users.count()).isEqualTo(2);
        assertThat(teams.count()).isEqualTo(2);
        assertThat(members.count()).isEqualTo(2);
        assertThat(notebooks.count()).isEqualTo(2);
        assertThat(notes.count()).isEqualTo(2);
        assertThat(history.count()).isEqualTo(2);
        assertThat(activity.count()).isEqualTo(2);
        for (int index = 1; index <= 2; index++) {
            UUID userId = id(1, index);
            UUID teamId = id(2, index);
            UserAccount user = users.findById(userId).orElseThrow();
            assertThat(user.getEmail()).endsWith("@scheideckerantunes.com");
            assertThat(new BCryptPasswordEncoder().matches("RestNotesDemo#8", user.getPasswordHash())).isTrue();
            assertThat(members.findById(new TeamMemberId(teamId, userId)).orElseThrow().getRole())
                    .isEqualTo(TeamRole.ADMIN);
            Note note = notes.findById(id(4, index)).orElseThrow();
            assertThat(note.getCreatedBy()).isEqualTo(userId);
            assertThat(note.getTeamId()).isEqualTo(teamId);
            assertThat(history.findByNoteId(note.getId(), PageRequest.of(0, 20)).getContent().getFirst()
                    .getContent()).isEqualTo(note.getContent());
            assertThat(activity.findByTeamId(teamId, PageRequest.of(0, 20)).getContent().getFirst()
                    .getAction()).isEqualTo("NOTE_CREATED");
        }
    }

    @Test
    void normalizedTagsAreUniqueWithinOneTeam() {
        tags.saveAndFlush(new Tag(ENG_TEAM, "design"));
        assertThatThrownBy(() -> tags.saveAndFlush(new Tag(ENG_TEAM, " DESIGN ")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void filtersMembershipBeforePaginationAndCounts() {
        PageRequest page = PageRequest.of(0, 1, Sort.by("id"));
        assertThat(teams.findVisibleTo(ENG_USER, page).getTotalElements()).isEqualTo(1);
        assertThat(notebooks.findVisibleTo(MKT_USER, page).getTotalElements()).isEqualTo(1);
        assertThat(notes.findVisibleTo(ENG_USER, page).getContent()).extracting(Note::getId)
                .containsExactly(ENG_NOTE);
        assertThat(notes.findVisibleById(MKT_NOTE, ENG_USER)).isEmpty();
        assertThat(notes.findVisibleById(ENG_NOTE, MKT_USER)).isEmpty();
        assertThat(notes.findVisibleTo(UUID.randomUUID(), page).getTotalElements()).isZero();
        assertThat(notes.findVisibleTo(ENG_USER, PageRequest.of(1, 1)).getContent()).isEmpty();
    }

    @Test
    void binaryUuidsAndUtcMicrosecondsRoundTrip() {
        Note note = notes.saveAndFlush(new Note(ENG_BOOK, ENG_TEAM, "Roundtrip", "Markdown body", ENG_USER));
        UUID noteId = note.getId();
        Instant created = note.getCreatedAt();
        entityManager.clear();
        Note reloaded = notes.findById(noteId).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getCreatedAt().getNano() % 1000).isZero();
        assertThat(jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM notes WHERE id = UUID_TO_BIN(?)",
                String.class, noteId.toString())).isEqualTo(noteId.toString());
    }

    @Test
    void mapsCommentAndTeamScopedTagRows() {
        Tag tag = tags.saveAndFlush(new Tag(ENG_TEAM, " Onboarding "));
        NoteTag association = noteTags.saveAndFlush(new NoteTag(ENG_NOTE, tag.getId(), ENG_TEAM));
        tags.saveAndFlush(new Tag(MKT_TEAM, "onboarding"));
        NoteComment comment = comments.saveAndFlush(new NoteComment(ENG_NOTE, ENG_USER, "Review ready"));
        entityManager.clear();
        assertThat(tags.findByTeamIdAndNormalizedName(ENG_TEAM, "onboarding").orElseThrow().getId())
                .isEqualTo(tag.getId());
        assertThat(noteTags.findById(association.getId()).orElseThrow().getTeamId()).isEqualTo(ENG_TEAM);
        assertThat(comments.findByNoteId(ENG_NOTE, PageRequest.of(0, 20)).getContent())
                .extracting(NoteComment::getId).contains(comment.getId());
    }

    @Test
    void rejectsCrossTeamNoteTagAssociation() {
        Tag tag = tags.saveAndFlush(new Tag(MKT_TEAM, "isolation"));
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO note_tags(note_id, tag_id, team_id) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?))",
                ENG_NOTE.toString(), tag.getId().toString(), ENG_TEAM.toString()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsNotebookTeamMismatch() {
        assertThatThrownBy(() -> notes.saveAndFlush(
                new Note(ENG_BOOK, MKT_TEAM, "Wrong tenant", "Must fail", MKT_USER)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void enforcesUniqueHistoryVersions() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO note_history SELECT UUID_TO_BIN(?), note_id, version_number, title, content, "
                        + "pinned, tags_snapshot, modified_by, created_at FROM note_history WHERE id = UUID_TO_BIN(?)",
                UUID.randomUUID().toString(), id(5, 1).toString())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsInvalidRoleAndDuplicateMembership() {
        assertThatThrownBy(() -> jdbc.update("UPDATE team_members SET role = 'admin' WHERE team_id = UUID_TO_BIN(?)",
                ENG_TEAM.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE team_members SET role = 'OWNER' WHERE team_id = UUID_TO_BIN(?)",
                ENG_TEAM.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO team_members SELECT team_id, user_id, role, joined_at "
                        + "FROM team_members WHERE team_id = UUID_TO_BIN(?)",
                ENG_TEAM.toString())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void normalizedEmailUniquenessIsEnforced() {
        assertThatThrownBy(() -> users.saveAndFlush(new UserAccount(
                " ENGINEERING.ADMIN@scheideckerantunes.com ", "unused-test-hash", "Duplicate")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void staleVersionCannotOverwriteANote() {
        Note stale = notes.findById(ENG_NOTE).orElseThrow();
        entityManager.detach(stale);
        jdbc.update("UPDATE notes SET version = version + 1 WHERE id = UUID_TO_BIN(?)", ENG_NOTE.toString());
        stale.replaceContent("Stale overwrite", "Must not win", ENG_USER);
        assertThatThrownBy(() -> notes.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void excludesBothArchivedNotesAndArchivedNotebooks() {
        jdbc.update("UPDATE notebooks SET archived_at = UTC_TIMESTAMP(6) WHERE id = UUID_TO_BIN(?)",
                ENG_BOOK.toString());
        assertThat(notes.findVisibleTo(ENG_USER, PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(notes.findVisibleById(ENG_NOTE, ENG_USER)).isEmpty();
        assertThat(notebooks.findVisibleTo(ENG_USER, PageRequest.of(0, 20)).getContent()).isEmpty();
        Note marketing = notes.findById(MKT_NOTE).orElseThrow();
        marketing.archive(Instant.now());
        notes.flush();
        assertThat(notes.findVisibleById(MKT_NOTE, MKT_USER)).isEmpty();
    }

    private static UUID id(int entity, int index) {
        return UUID.fromString(entity + "0000000-0000-4000-8000-00000000000" + index);
    }
}
