package com.scheideckerantunes.notes.common;

/**
 * Uses the same public response for missing and inaccessible tenant resources.
 *
 * @author Carlos Scheidecker
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Resource not found");
    }
}
