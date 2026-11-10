package io.github.rahulbsw.protoweft.argi;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ARGI's HTML-to-Markdown converter still works on the patched jsoup this module pins. */
class JsoupOverrideTest {

    @Test
    void flexmarkConvertsHtmlWithThePatchedJsoup() {
        assertThat(Jsoup.class.getPackage().getImplementationVersion()).isEqualTo("1.24.1");
        String markdown = FlexmarkHtmlConverter.builder().build()
                .convert("<h1>Title</h1><p>Some <b>bold</b> text and a <a href=\"https://example.com\">link</a>.</p><ul><li>one</li></ul>");
        assertThat(markdown).contains("Title\n=====").contains("**bold**").contains("[link](https://example.com)").contains("* one");
    }
}
