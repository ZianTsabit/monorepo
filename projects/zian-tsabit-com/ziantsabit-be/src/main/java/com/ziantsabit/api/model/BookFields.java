package com.ziantsabit.api.model;

import java.util.ArrayList;
import java.util.List;

/** The writable half of a book, as a form fills it in. */
public final class BookFields {

    public String title = "";
    /** Blank means "derive one" when the book is saved. */
    public String slug = "";
    public String author = "";
    public List<String> genres = new ArrayList<>();
    public String isbn = "";
    public Integer releaseYear;
    public String review = "";
    public String coverImageUrl = "";
    public String coverImageAlt = "";
    public String status = Book.DRAFT;

    public static BookFields of(Book book) {
        BookFields fields = new BookFields();
        fields.title = book.title();
        fields.slug = book.slug();
        fields.author = book.author();
        fields.genres = new ArrayList<>(book.genres());
        fields.isbn = book.isbn();
        fields.releaseYear = book.releaseYear();
        fields.review = book.review();
        fields.coverImageUrl = book.coverImageUrl();
        fields.coverImageAlt = book.coverImageAlt();
        fields.status = book.status();
        return fields;
    }
}
