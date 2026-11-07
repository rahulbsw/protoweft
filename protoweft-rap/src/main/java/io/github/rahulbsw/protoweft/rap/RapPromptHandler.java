package io.github.rahulbsw.protoweft.rap;

import java.util.List;

/**
 * Surfaces a RAP tool's requests for user interaction. Runs on the connector's executor, never
 * on a callback request thread, so it may block while the user decides.
 */
public interface RapPromptHandler {

    /**
     * The tool needs the user to authorize access at {@code authUrl}. Show it to the user; the call
     * stays pending until the tool delivers its result. Don't store the URL beyond that.
     */
    void onAuthorization(String authUrl, RapCall call);

    /**
     * The tool asks the user to choose.
     *
     * @param defaultIndex the index to use if the user dismisses the prompt
     * @return the chosen index; an out-of-range value or an exception counts as dismissal
     */
    int onChoice(String prompt, List<String> choices, int defaultIndex, RapCall call);
}
