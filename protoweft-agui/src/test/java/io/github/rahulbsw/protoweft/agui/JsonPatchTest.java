package io.github.rahulbsw.protoweft.agui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RFC 6902 behaviour, using the RFC's Appendix A examples where they apply. */
class JsonPatchTest {

    private static Object apply(String doc, String patch) {
        return JsonPatch.apply(Json.parse(doc), (List<?>) Json.parse(patch));
    }

    private static void check(String doc, String patch, String expected) {
        assertThat(JsonPatch.equal(apply(doc, patch), Json.parse(expected))).as(patch).isTrue();
    }

    @Test
    void appendixA() {
        check("{\"foo\":\"bar\"}", "[{\"op\":\"add\",\"path\":\"/baz\",\"value\":\"qux\"}]", "{\"baz\":\"qux\",\"foo\":\"bar\"}");
        check("{\"foo\":[\"bar\",\"baz\"]}", "[{\"op\":\"add\",\"path\":\"/foo/1\",\"value\":\"qux\"}]",
                "{\"foo\":[\"bar\",\"qux\",\"baz\"]}");
        check("{\"baz\":\"qux\",\"foo\":\"bar\"}", "[{\"op\":\"remove\",\"path\":\"/baz\"}]", "{\"foo\":\"bar\"}");
        check("{\"foo\":[\"bar\",\"qux\",\"baz\"]}", "[{\"op\":\"remove\",\"path\":\"/foo/1\"}]", "{\"foo\":[\"bar\",\"baz\"]}");
        check("{\"baz\":\"qux\",\"foo\":\"bar\"}", "[{\"op\":\"replace\",\"path\":\"/baz\",\"value\":\"boo\"}]",
                "{\"baz\":\"boo\",\"foo\":\"bar\"}");
        check("{\"foo\":{\"bar\":\"baz\",\"waldo\":\"fred\"},\"qux\":{\"corge\":\"grault\"}}",
                "[{\"op\":\"move\",\"from\":\"/foo/waldo\",\"path\":\"/qux/thud\"}]",
                "{\"foo\":{\"bar\":\"baz\"},\"qux\":{\"corge\":\"grault\",\"thud\":\"fred\"}}");
        check("{\"foo\":[\"all\",\"grass\",\"cows\",\"eat\"]}", "[{\"op\":\"move\",\"from\":\"/foo/1\",\"path\":\"/foo/3\"}]",
                "{\"foo\":[\"all\",\"cows\",\"eat\",\"grass\"]}");
        check("{\"baz\":\"qux\",\"foo\":[\"a\",2,\"c\"]}",
                "[{\"op\":\"test\",\"path\":\"/baz\",\"value\":\"qux\"},{\"op\":\"test\",\"path\":\"/foo/1\",\"value\":2}]",
                "{\"baz\":\"qux\",\"foo\":[\"a\",2,\"c\"]}");
        check("{\"foo\":\"bar\"}", "[{\"op\":\"add\",\"path\":\"/child\",\"value\":{\"grandchild\":{}}}]",
                "{\"foo\":\"bar\",\"child\":{\"grandchild\":{}}}");
        check("{\"foo\":\"bar\"}", "[{\"op\":\"add\",\"path\":\"/baz\",\"value\":\"qux\",\"xyz\":123}]",
                "{\"foo\":\"bar\",\"baz\":\"qux\"}");
        check("{\"/\":9,\"~1\":10}", "[{\"op\":\"test\",\"path\":\"/~01\",\"value\":10}]", "{\"/\":9,\"~1\":10}");
        check("{\"foo\":[\"bar\"]}", "[{\"op\":\"add\",\"path\":\"/foo/-\",\"value\":[\"abc\",\"def\"]}]",
                "{\"foo\":[\"bar\",[\"abc\",\"def\"]]}");
        check("{\"a\":1}", "[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/b\"}]", "{\"a\":1,\"b\":1}");
        check("{\"a\":1}", "[{\"op\":\"replace\",\"path\":\"\",\"value\":[1]}]", "[1]");
    }

    @Test
    void failuresAreAtomic() {
        Object doc = Json.parse("{\"baz\":\"qux\"}");
        assertThatThrownBy(() -> JsonPatch.apply(doc, (List<?>) Json.parse(
                "[{\"op\":\"add\",\"path\":\"/x\",\"value\":1},{\"op\":\"test\",\"path\":\"/baz\",\"value\":\"bar\"}]")))
                .isInstanceOf(JsonPatch.PatchException.class);
        assertThat(doc).isEqualTo(Map.of("baz", "qux"));
        assertThatThrownBy(() -> apply("{\"baz\":\"qux\"}", "[{\"op\":\"add\",\"path\":\"/baz/bat\",\"value\":\"qux\"}]"))
                .isInstanceOf(JsonPatch.PatchException.class);
        assertThatThrownBy(() -> apply("{\"foo\":[\"bar\"]}", "[{\"op\":\"add\",\"path\":\"/foo/2\",\"value\":1}]"))
                .isInstanceOf(JsonPatch.PatchException.class);
        assertThatThrownBy(() -> apply("{\"foo\":[\"bar\"]}", "[{\"op\":\"remove\",\"path\":\"/foo/01\"}]"))
                .isInstanceOf(JsonPatch.PatchException.class);
        assertThatThrownBy(() -> apply("{\"a\":{}}", "[{\"op\":\"move\",\"from\":\"/a\",\"path\":\"/a/b\"}]"))
                .isInstanceOf(JsonPatch.PatchException.class);
        assertThatThrownBy(() -> apply("{}", "[{\"op\":\"remove\",\"path\":\"/missing\"}]"))
                .isInstanceOf(JsonPatch.PatchException.class);
    }

    @Test
    void numbersCompareByValue() {
        assertThat(JsonPatch.equal(Json.parse("1"), Json.parse("1.0"))).isTrue();
        assertThat(JsonPatch.equal(Json.parse("{\"a\":1,\"b\":2}"), Json.parse("{\"b\":2,\"a\":1}"))).isTrue();
        assertThat(JsonPatch.equal(Json.parse("[1,2]"), Json.parse("[2,1]"))).isFalse();
    }
}
