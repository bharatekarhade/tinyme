package com.tinyme.domain.health.model;

/**
 * A synced item the server won't store. {@code index} points at it in "items" or "deleted", so the phone can
 * drop that one and send the rest; it is null when the batch itself is wrong.
 */
public class InvalidHealthItemException extends IllegalArgumentException {
    public enum Part {
        ITEMS("items", "index"),
        DELETED("deleted", "deleted_index");

        private final String list;
        private final String indexProperty;

        Part(String list, String indexProperty) {
            this.list = list;
            this.indexProperty = indexProperty;
        }

        /** The ProblemDetail property the index goes in. */
        public String indexProperty() {
            return indexProperty;
        }
    }

    private final Part part;
    private final Integer index;

    public InvalidHealthItemException(Integer index, String message) {
        this(Part.ITEMS, index, message);
    }

    public InvalidHealthItemException(Part part, Integer index, String message) {
        super(index == null ? message : part.list + "[" + index + "]: " + message);
        this.part = part;
        this.index = index;
    }

    public Part part() {
        return part;
    }

    public Integer index() {
        return index;
    }
}
