package com.cielvitrine.app;

import com.yandex.authsdk.YandexAuthResult;
import com.yandex.authsdk.YandexAuthToken;

/** Java accessors avoid an AGP 9.1/Kotlin UAST crash on SDK constructor properties. */
public final class YandexAuthResultBridge {
    private YandexAuthResultBridge() {}

    public static void deliver(YandexAuthResult result, YandexCloudModel model) {
        if (result instanceof YandexAuthResult.Success) {
            YandexAuthToken token = ((YandexAuthResult.Success) result).getToken();
            model.loginSucceeded(token.getValue(), token.getExpiresIn());
        } else if (result instanceof YandexAuthResult.Failure) {
            model.loginFailed();
        } else {
            model.loginCancelled();
        }
    }
}
