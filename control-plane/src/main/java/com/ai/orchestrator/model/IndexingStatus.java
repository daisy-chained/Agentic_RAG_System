package com.ai.orchestrator.model;

/**
 * Tracks the lifecycle of a document through the indexing pipeline.
 *
 * <p>Used by {@link DocumentMetadata} to reflect where a given file sits in the
 * ingestion process. Stored as a {@code VARCHAR} column via {@code @Enumerated(EnumType.STRING)}.
 */
public enum IndexingStatus {

    /** File has been registered but processing has not started. */
    PENDING,

    /** Chunking and embedding generation is in progress. */
    INDEXING,

    /** All chunks are embedded and stored in the vector database. */
    INDEXED,

    /** The indexing pipeline encountered an unrecoverable error. */
    FAILED
}
