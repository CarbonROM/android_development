/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.autofillkeyboard;

import android.inputmethodservice.InputMethodService;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.annotation.GuardedBy;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inline.InlinePresentationSpec;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The {@link InputMethodService} implementation for Autofill keyboard. */
public class AutofillImeService extends InputMethodService {

    private InputView mInputView;
    private Decoder mDecoder;
    private LinearLayout mSuggestionStrip;

    @Override
    public View onCreateInputView() {
        mInputView = (InputView) LayoutInflater.from(this).inflate(R.layout.input_view, null);
        return mInputView;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        mDecoder = new Decoder(getCurrentInputConnection());
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        mInputView.removeAllViews();
        Keyboard keyboard = Keyboard.qwerty(this);
        mInputView.addView(keyboard.inflateKeyboardView(LayoutInflater.from(this), mInputView));
        mSuggestionStrip = mInputView.findViewById(R.id.suggestion_view);
    }

    @Override
    public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (mInputView != null) {
            outInsets.contentTopInsets += mInputView.getTopInsets();
        }
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT;
    }

    /*****************    Inline Suggestions Demo Code   *****************/

    private static final String TAG = "AutofillImeService";

    private Handler mMainHandler = new Handler();

    @GuardedBy("this")
    private List<View> mSuggestionViews = new ArrayList<>();
    @GuardedBy("this")
    private List<Size> mSuggestionViewSizes = new ArrayList<>();
    @GuardedBy("this")
    private boolean mSuggestionViewVisible = false;

    @Override
    public InlineSuggestionsRequest onCreateInlineSuggestionsRequest() {
        Log.d(TAG, "onCreateInlineSuggestionsRequest() called");
        final ArrayList<InlinePresentationSpec> presentationSpecs = new ArrayList<>();
        presentationSpecs.add(new InlinePresentationSpec.Builder(new Size(100, 100),
                new Size(400, 100)).setStyle(
                getResources().getResourceName(R.style.YellowTheme)).build());
        presentationSpecs.add(new InlinePresentationSpec.Builder(new Size(100, 100),
                new Size(400, 100)).setStyle(
                getResources().getResourceName(R.style.GreenTheme)).build());

        return new InlineSuggestionsRequest.Builder(presentationSpecs)
                .setMaxSuggestionCount(6)
                .build();
    }

    @Override
    public boolean onInlineSuggestionsResponse(InlineSuggestionsResponse response) {
        Log.d(TAG, "onInlineSuggestionsResponse() called");
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            onInlineSuggestionsResponseInternal(response);
        });
        return true;
    }

    private synchronized void updateInlineSuggestionVisibility(boolean visible, boolean force) {
        Log.d(TAG, "updateInlineSuggestionVisibility() called, visible=" + visible + ", force="
                + force);
        mMainHandler.post(() -> {
            Log.d(TAG, "updateInlineSuggestionVisibility() running");
            if (visible == mSuggestionViewVisible && !force) {
                return;
            } else if (visible) {
                mSuggestionStrip.removeAllViews();
                final int size = mSuggestionViews.size();
                for (int i = 0; i < size; i++) {
                    if(mSuggestionViews.get(i) == null) {
                        continue;
                    }
                    ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
                            mSuggestionViewSizes.get(i).getWidth(),
                            mSuggestionViewSizes.get(i).getHeight());
                    mSuggestionStrip.addView(mSuggestionViews.get(i), layoutParams);
                }
                mSuggestionViewVisible = true;
            } else {
                mSuggestionStrip.removeAllViews();
                mSuggestionViewVisible = false;
            }
        });
    }

    private synchronized void updateSuggestionViews(View[] suggestionViews, Size[] sizes) {
        Log.d(TAG, "updateSuggestionViews() called");
        mSuggestionViews = Arrays.asList(suggestionViews);
        mSuggestionViewSizes = Arrays.asList(sizes);
        updateInlineSuggestionVisibility(true, true);
    }

    private void onInlineSuggestionsResponseInternal(InlineSuggestionsResponse response) {
        Log.d(TAG, "onInlineSuggestionsResponseInternal() called. Suggestion="
                + response.getInlineSuggestions().size());

        final List<InlineSuggestion> inlineSuggestions = response.getInlineSuggestions();
        final int totalSuggestionsCount = inlineSuggestions.size();
        final AtomicInteger suggestionsCount = new AtomicInteger(totalSuggestionsCount);
        final View[] suggestionViews = new View[totalSuggestionsCount];
        final Size[] sizes = new Size[totalSuggestionsCount];

        for (int i=0; i<totalSuggestionsCount; i++) {
            final int index = i;
            InlineSuggestion inlineSuggestion = inlineSuggestions.get(index);
            Size size = inlineSuggestion.getInfo().getPresentationSpec().getMaxSize();
            inlineSuggestion.inflate(this, size,
                    AsyncTask.THREAD_POOL_EXECUTOR,
                    suggestionView -> {
                        Log.d(TAG, "new inline suggestion view ready");
                        if(suggestionView != null) {
                            suggestionViews[index] = suggestionView;
                            sizes[index] = size;
                            suggestionView.setOnTouchListener((v, event) -> {
                                Toast.makeText(AutofillImeService.this, "hello",
                                        Toast.LENGTH_LONG).show();
                                return false;
                            });
                        }
                        if (suggestionsCount.decrementAndGet() == 0) {
                            updateSuggestionViews(suggestionViews, sizes);
                        }
                    });
        }
    }

    void handle(String data) {
        Log.d(TAG, "handle() called: [" + data + "]");
        mDecoder.decode(data);
        updateInlineSuggestionVisibility(mDecoder.isEmpty(), false);
    }
}