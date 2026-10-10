package com.ziantsabit.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziantsabit.api.model.Book;
import com.ziantsabit.api.model.Post;
import com.ziantsabit.api.support.Api;
import com.ziantsabit.api.support.ApiTestBase;
import com.ziantsabit.api.support.Response;
import com.ziantsabit.api.validation.Isbn;

/** Ported from the Django suite's BookModelTests, BookAPITests and BookPaginationTests. */
class BookApiTest extends ApiTestBase {

    static String detail(Book book) {
        return "/api/books/" + book.slug() + "/";
    }

    static Map<String, Object> payload(Object... overrides) {
        Map<String, Object> body = new HashMap<>(Map.of("title", "The Dispossessed", "author", "Ursula K. Le Guin"));
        for (int index = 0; index < overrides.length; index += 2) {
            body.put((String) overrides[index], overrides[index + 1]);
        }
        return body;
    }

    @Nested
    class Model {

        @Test
        void slugIsDerivedFromTitle() {
            assertThat(book("The Dispossessed", "Ursula K. Le Guin").slug()).isEqualTo("the-dispossessed");
        }

        @Test
        void aSecondBookWithTheSameTitleIsSluggedByAuthor() {
            book("Ulysses", "James Joyce");
            assertThat(book("Ulysses", "Alfred Tennyson").slug()).isEqualTo("ulysses-alfred-tennyson");
        }

        @Test
        void aThirdBookWithTheSameTitleAndAuthorFallsBackToANumber() {
            book("Ulysses", "James Joyce");
            assertThat(book("Ulysses", "James Joyce").slug()).isEqualTo("ulysses-james-joyce");
            assertThat(book("Ulysses", "James Joyce").slug()).isEqualTo("ulysses-james-joyce-2");
        }

        @Test
        void explicitSlugIsKept() {
            assertThat(book("Dune", "Frank Herbert", b -> b.slug = "the-one").slug()).isEqualTo("the-one");
        }

        @Test
        void aBookAndAPostMayShareASlug() {
            post("Dune");
            assertThat(book("Dune", "Frank Herbert").slug()).isEqualTo("dune");
        }

        @Test
        void genresAreTrimmedDedupedAndCaseFolded() {
            Book book = book("Neuromancer", "William Gibson",
                    b -> b.genres = List.of("  Sci-Fi ", "sci-fi", "", "Cyberpunk", "SCI-FI"));
            assertThat(book.genres()).containsExactly("Sci-Fi", "Cyberpunk");
        }

        @Test
        void isbnSeparatorsAreStrippedOnSave() {
            Book book = book("Clean Code", "Robert C. Martin", b -> b.isbn = "978-0-13-235088-4");
            assertThat(book.isbn()).isEqualTo("9780132350884");
        }

        @Test
        void isbn10CheckDigit() {
            assertThat(Isbn.isValid("0306406152")).isTrue();
            assertThat(Isbn.isValid("080442957X")).isTrue();
            assertThat(Isbn.isValid("0306406125")).isFalse();
            assertThat(Isbn.isValid("030640615")).isFalse();
        }

        @Test
        void isbn13CheckDigit() {
            assertThat(Isbn.isValid("9780132350884")).isTrue();
            assertThat(Isbn.isValid("9780132350885")).isFalse();
        }

        @Test
        void normaliseIsbnHandlesSpacesDashesAndLowercaseX() {
            assertThat(Isbn.normalise(" 0-8044 2957 x ")).isEqualTo("080442957X");
        }

        @Test
        void statusDefaultsToDraft() {
            assertThat(book("Dune", "Frank Herbert").status()).isEqualTo(Book.DRAFT);
        }
    }

    @Nested
    class Api {

        Book dune;
        Book neuromancer;
        Book unshelved;

        @BeforeEach
        void fixtures() {
            createUser();
            dune = book("Dune", "Frank Herbert", b -> {
                b.genres = List.of("Sci-Fi", "Classic");
                b.isbn = "9780441013593";
                b.releaseYear = 1965;
                b.status = Book.PUBLISHED;
            });
            neuromancer = book("Neuromancer", "William Gibson", b -> {
                b.genres = List.of("sci-fi", "Cyberpunk");
                b.releaseYear = 1984;
                b.status = Book.PUBLISHED;
            });
            unshelved = book("Half-Read Thing", "Nobody", b -> b.genres = List.of("Unfinished"));
        }

        Map<String, Object> genreCounts(Response response) {
            Map<String, Object> counts = new HashMap<>();
            for (Map<String, Object> row : response.list()) {
                counts.put((String) row.get("name"), row.get("count"));
            }
            return counts;
        }

        // --- read ---------------------------------------------------------

        @Test
        void anonymousListShowsOnlyPublished() {
            Response response = client.get("/api/books/");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.slugSet()).isEqualTo(Set.of(dune.slug(), neuromancer.slug()));
        }

        @Test
        void anonymousCannotRetrieveADraftBySlug() {
            assertThat(client.get(detail(unshelved)).status()).isEqualTo(404);
        }

        @Test
        void authenticatedListIncludesDrafts() {
            assertThat(owner().get("/api/books/").results()).hasSize(3);
        }

        @Test
        void detailCarriesEveryCataloguedField() {
            Response response = client.get(detail(dune));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.map().keySet()).containsExactly("id", "title", "slug", "author", "genres", "isbn",
                    "release_year", "review", "cover_image_url", "cover_image_alt", "status", "created_at",
                    "updated_at");
            assertThat(response.string("author")).isEqualTo("Frank Herbert");
            assertThat(response.get("genres")).isEqualTo(List.of("Sci-Fi", "Classic"));
            assertThat(response.string("isbn")).isEqualTo("9780441013593");
            assertThat(response.get("release_year")).isEqualTo(1965);
        }

        @Test
        void filterByGenreIgnoresCase() {
            Response response = client.get("/api/books/", "genre", "SCI-FI");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.slugSet()).isEqualTo(Set.of(dune.slug(), neuromancer.slug()));
        }

        @Test
        void filterByUnusedGenreReturnsNothingRatherThanErroring() {
            Response response = client.get("/api/books/", "genre", "westerns");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.results()).isEmpty();
        }

        @Test
        void genreFilterDoesNotLeakDrafts() {
            assertThat(client.get("/api/books/", "genre", "Unfinished").results()).isEmpty();
        }

        @Test
        void searchMatchesTitleAndAuthor() {
            assertThat(client.get("/api/books/", "search", "neuro").slugs()).containsExactly(neuromancer.slug());
            assertThat(client.get("/api/books/", "search", "herbert").slugs()).containsExactly(dune.slug());
        }

        @Test
        void searchMatchesAHyphenatedIsbn() {
            assertThat(client.get("/api/books/", "search", "978-0-441-01359-3").slugs()).containsExactly(dune.slug());
        }

        @Test
        void searchTreatsLikeWildcardsAsText() {
            assertThat(client.get("/api/books/", "search", "%").results()).isEmpty();
        }

        @Test
        void orderingByTitle() {
            assertThat(client.get("/api/books/", "ordering", "title").column("title"))
                    .containsExactly("Dune", "Neuromancer");
        }

        @Test
        void orderingByYearPutsUndatedBooksLast() {
            Book undated = book("Anonymous Chapbook", "Unknown", b -> b.status = Book.PUBLISHED);
            assertThat(client.get("/api/books/", "ordering", "year").slugs())
                    .containsExactly(neuromancer.slug(), dune.slug(), undated.slug());
        }

        @Test
        void unknownOrderingIsRejected() {
            Response response = client.get("/api/books/", "ordering", "titel");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("ordering")).isEqualTo(
                    List.of("'titel' is not a valid ordering. Choose from: author, recent, title, year."));
        }

        @Test
        void unknownStatusIsRejected() {
            assertThat(owner().get("/api/books/", "status", "pubished").status()).isEqualTo(400);
        }

        @Test
        void anonymousStatusFilterCannotSurfaceDrafts() {
            Response response = client.get("/api/books/", "status", "draft");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.results()).hasSize(2);
        }

        // --- genres action ------------------------------------------------

        @Test
        void genresAreCountedAcrossBooks() {
            Response response = client.get("/api/books/genres/");
            assertThat(response.status()).isEqualTo(200);
            Map<String, Object> counts = genreCounts(response);
            assertThat(counts.get("Cyberpunk")).isEqualTo(1);
            assertThat(counts).doesNotContainKey("sci-fi");
            assertThat(counts.get("Sci-Fi")).isEqualTo(2);
        }

        @Test
        void theCommonestSpellingOfAGenreIsTheOneOffered() {
            for (String title : List.of("A", "B")) {
                book(title, "Someone", b -> {
                    b.genres = List.of("cyberpunk");
                    b.status = Book.PUBLISHED;
                });
            }
            Map<String, Object> counts = genreCounts(client.get("/api/books/genres/"));
            assertThat(counts.get("cyberpunk")).isEqualTo(3);
            assertThat(counts).doesNotContainKey("Cyberpunk");
        }

        @Test
        void genresAreOrderedCommonestFirst() {
            List<Object> names = client.get("/api/books/genres/").list().stream().map(r -> r.get("name")).toList();
            assertThat(names).containsExactly("Sci-Fi", "Classic", "Cyberpunk");
        }

        @Test
        void genresExcludeDraftsForAnonymousCallers() {
            assertThat(genreCounts(client.get("/api/books/genres/"))).doesNotContainKey("Unfinished");
        }

        @Test
        void genresIncludeDraftsForTheOwner() {
            assertThat(genreCounts(owner().get("/api/books/genres/"))).containsKey("Unfinished");
        }

        // --- write --------------------------------------------------------

        @Test
        void anonymousCannotCreate() {
            assertThat(client.post("/api/books/", payload()).status()).isEqualTo(403);
        }

        @Test
        void authenticatedCreate() {
            Response response = owner().post("/api/books/",
                    payload("genres", List.of("Sci-Fi", "sci-fi "), "isbn", "0-06-051280-6"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.string("slug")).isEqualTo("the-dispossessed");
            assertThat(response.get("genres")).isEqualTo(List.of("Sci-Fi"));
            assertThat(response.string("isbn")).isEqualTo("0060512806");
            assertThat(response.string("status")).isEqualTo("draft");
        }

        @Test
        void basicAuthCreate() {
            assertThat(owner().post("/api/books/", payload()).status()).isEqualTo(201);
        }

        @Test
        void authorIsRequired() {
            Response response = owner().post("/api/books/", body("title", "Untitled"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("author");
        }

        @Test
        void blankAuthorIsRejected() {
            Response response = owner().post("/api/books/", payload("author", ""));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.get("author")).isEqualTo(List.of("This field may not be blank."));
        }

        @Test
        void aDuplicateTitleAndAuthorIsRejected() {
            Response response = owner().post("/api/books/", payload("title", "Dune", "author", "Frank Herbert"));
            assertThat(response.status()).isEqualTo(400);
            @SuppressWarnings("unchecked")
            List<String> errors = (List<String>) response.get("non_field_errors");
            assertThat(errors.get(0)).contains("dune");
        }

        @Test
        void theDuplicateCheckIgnoresCase() {
            assertThat(owner().post("/api/books/", payload("title", "dune", "author", "frank herbert")).status())
                    .isEqualTo(400);
        }

        @Test
        void theDuplicateCheckSeesDrafts() {
            assertThat(owner().post("/api/books/", payload("title", "Half-Read Thing", "author", "Nobody")).status())
                    .isEqualTo(400);
        }

        @Test
        void theSameTitleByADifferentAuthorIsAccepted() {
            Response response = owner().post("/api/books/", payload("title", "Dune", "author", "Someone Else"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.string("slug")).isEqualTo("dune-someone-else");
        }

        @Test
        void anotherBookByTheSameAuthorIsAccepted() {
            assertThat(owner().post("/api/books/", payload("title", "Dune Messiah", "author", "Frank Herbert"))
                    .status()).isEqualTo(201);
        }

        @Test
        void aBookDoesNotCollideWithItselfOnUpdate() {
            assertThat(owner().patch(detail(dune),
                    body("title", "Dune", "author", "Frank Herbert", "review", "Still holds up.")).status())
                    .isEqualTo(200);
        }

        @Test
        void renamingABookOntoAnotherIsRejected() {
            Response response = owner().patch(detail(neuromancer), body("title", "Dune", "author", "Frank Herbert"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("non_field_errors");
        }

        @Test
        void aPatchOfOneHalfIsCheckedAgainstTheStoredOther() {
            book("Dune", "Someone Else");
            Response response = owner().patch("/api/books/dune-someone-else/", body("author", "Frank Herbert"));
            assertThat(response.status()).isEqualTo(400);
        }

        @Test
        void badIsbnIsRejected() {
            Response response = owner().post("/api/books/", payload("isbn", "978-0-13-235088-5"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("isbn");
        }

        @Test
        void blankIsbnIsAccepted() {
            Response response = owner().post("/api/books/", payload("isbn", ""));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.string("isbn")).isEmpty();
        }

        @Test
        void impossibleReleaseYearsAreRejected() {
            for (int year : List.of(19, 1200, today().getYear() + 5)) {
                Response response = owner().post("/api/books/", payload("release_year", year));
                assertThat(response.status()).as("year %d", year).isEqualTo(400);
                assertThat(response.map()).containsKey("release_year");
            }
        }

        @Test
        void nextYearIsAccepted() {
            assertThat(owner().post("/api/books/", payload("release_year", today().getYear() + 1)).status())
                    .isEqualTo(201);
        }

        @Test
        void releaseYearCanBeCleared() {
            Response response = owner().patch(detail(dune), body("release_year", null));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.map()).containsEntry("release_year", null);
        }

        @Test
        void blankSlugIsRejectedRatherThanColliding() {
            Response response = owner().post("/api/books/", payload("slug", "   "));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.map()).containsKey("slug");
        }

        @Test
        void patchKeepsTheUrlWhenSlugIsOmitted() {
            Response response = owner().patch(detail(dune), body("review", "Still holds up."));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.string("slug")).isEqualTo("dune");
            assertThat(response.string("review")).isEqualTo("Still holds up.");
        }

        @Test
        void publishingIsAStatusChange() {
            Response response = owner().patch(detail(unshelved), body("status", "published"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.string("status")).isEqualTo("published");
            assertThat(client.get("/api/books/").get("count")).isEqualTo(3);
        }

        @Test
        void anonymousCannotDelete() {
            assertThat(client.delete(detail(dune)).status()).isEqualTo(403);
        }

        @Test
        void authenticatedDelete() {
            assertThat(owner().delete(detail(dune)).status()).isEqualTo(204);
            assertThat(exists("myapp_book", dune.id())).isFalse();
        }

        @Test
        void deletingABookLeavesPostsAlone() {
            Post post = post("On Dune", p -> p.tags = List.of("Books"));
            owner().delete(detail(dune));
            assertThat(exists("myapp_post", post.id())).isTrue();
        }
    }

    @Nested
    class Pagination {

        static final int PAGE = BookController.PAGE_SIZE;

        @BeforeEach
        void fixtures() {
            for (int index = 0; index < PAGE + 2; index++) {
                String title = String.format("Book %02d", index);
                book(title, "Someone", b -> b.status = Book.PUBLISHED);
            }
        }

        @Test
        void aPageIsShorterThanTheSiteWideOne() {
            Response response = client.get("/api/books/");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.results()).hasSize(PAGE);
            assertThat(PAGE).isLessThan(20);
        }

        @Test
        void theCountIsTheWholeCatalogueNotThePage() {
            assertThat(client.get("/api/books/").get("count")).isEqualTo(PAGE + 2);
        }

        @Test
        void aFullPageOffersTheNextOne() {
            Response response = client.get("/api/books/");
            assertThat(response.string("next")).isEqualTo(base() + "/api/books/?page=2");
            assertThat(response.get("previous")).isNull();
        }

        @Test
        void theNextLinkCanBeFollowedAsGiven() {
            Response second = client.get(client.get("/api/books/").string("next"));
            assertThat(second.results()).hasSize(2);
            assertThat(second.string("previous")).isEqualTo(base() + "/api/books/");
        }

        @Test
        void theSecondPageHoldsTheRemainder() {
            Response response = client.get("/api/books/", "page", "2");
            assertThat(response.results()).hasSize(2);
            assertThat(response.get("next")).isNull();
        }

        @Test
        void aPagePastTheEndIsA404() {
            Response response = client.get("/api/books/", "page", "3");
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.string("detail")).isEqualTo("Invalid page.");
            assertThat(client.get("/api/books/", "page", "abc").status()).isEqualTo(404);
        }

        @Test
        void theTwoPagesDoNotOverlap() {
            List<Object> slugs = new java.util.ArrayList<>(client.get("/api/books/").slugs());
            slugs.addAll(client.get("/api/books/", "page", "2").slugs());
            assertThat(slugs).doesNotHaveDuplicates().hasSize(PAGE + 2);
        }

        @Test
        void pagingSurvivesAFilter() {
            assertThat(client.get("/api/books/", "ordering", "title").results()).hasSize(PAGE);
        }

        @Test
        void postsKeepTheSiteWidePageSize() {
            for (int index = 0; index < 22; index++) {
                published(String.format("Post %02d", index));
            }
            assertThat(client.get("/api/posts/").results()).hasSize(20);
        }
    }
}
