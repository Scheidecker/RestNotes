package com.scheideckerantunes.notes.note;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Size;

/**
 * Presence flags distinguish omission from explicit null without an extra JSON library.
 *
 * @author Carlos Scheidecker
 */
public final class PatchNoteRequest {
    @Size(min = 1, max = 255)
    private String title;
    @NoteContentSize
    private String content;
    private Boolean pinned;
    private boolean titlePresent;
    private boolean contentPresent;
    private boolean pinnedPresent;

    @JsonSetter("title")
    public void setTitle(String title) {
        titlePresent = true;
        this.title = title == null ? null : title.strip();
    }

    @JsonSetter("content")
    public void setContent(String content) {
        contentPresent = true;
        this.content = content;
    }

    @JsonSetter("pinned")
    public void setPinned(Boolean pinned) {
        pinnedPresent = true;
        this.pinned = pinned;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Boolean getPinned() {
        return pinned;
    }

    public boolean titlePresent() {
        return titlePresent;
    }

    public boolean contentPresent() {
        return contentPresent;
    }

    public boolean pinnedPresent() {
        return pinnedPresent;
    }

    @Override
    public String toString() {
        return "PatchNoteRequest[title=REDACTED, content=REDACTED]";
    }
}
