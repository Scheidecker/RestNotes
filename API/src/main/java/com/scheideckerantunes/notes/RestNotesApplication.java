package com.scheideckerantunes.notes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the RestNotes API.
 *
 * @author Carlos Scheidecker
 */
@SpringBootApplication
public class RestNotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestNotesApplication.class, args);
    }
}
