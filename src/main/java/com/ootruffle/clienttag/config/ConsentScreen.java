package com.ootruffle.clienttag.config;

import androidx.compose.runtime.Composer;
import com.ootruffle.clienttag.platform.Platform;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import org.polyfrost.compose.host.ComposeOptions;
import org.polyfrost.compose.minecraft.screen.ComposeScreen;

/**
 * Hosts the consent question ({@code ConsentDialog.kt}) as a PolyCompose screen. It's Java rather
 * than Kotlin because on 1.8.9 PolyCompose's Kotlin metadata still names its Minecraft supertype in
 * intermediary, which the Kotlin compiler can't resolve. Only touched through {@link ConsentPrompt},
 * which checks PolyCompose is loaded first.
 */
final class ConsentScreen extends ComposeScreen {

    private static ConsentScreen current;

    private final Object previous;
    private final Function2<Composer, Integer, Unit> content;
    private boolean answered;

    private ConsentScreen(String title, String message, String yes, String no, int delaySeconds, Object previous, Consumer<Boolean> onAnswer) {
        super(RenderMode.ON_DEMAND, ComposeOptions.Default);
        this.previous = previous;
        this.content = ConsentDialogKt.consentContent(title, message, yes, no, System.currentTimeMillis() + delaySeconds * 1000L, answer -> {
            answer(answer, onAnswer);
            return Unit.INSTANCE;
        });
    }

    @Override
    public void compose(Composer composer, int changed) {
        content.invoke(composer, 0);
    }

    /** Blurs whatever is behind the question - the world or the menu it opened over. */
    @Override
    protected float blurAlpha() {
        return 1f;
    }

    private void answer(boolean yes, Consumer<Boolean> onAnswer) {
        if (answered) {
            return;
        }
        answered = true;
        if (current == this) {
            current = null;
        }
        onAnswer.accept(yes);
        Platform.get().setCurrentScreen(previous);
    }

    /** Shows the question over whatever screen is open, and puts that screen back once it's answered. */
    static void open(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer) {
        final Platform platform = Platform.get();
        current = new ConsentScreen(title, message, yes, no, delaySeconds, platform.currentScreen(), onAnswer);
        platform.setCurrentScreen(current);
    }

    /** Whether the question is the open screen (something else may have replaced it). */
    static boolean isShowing() {
        return current != null && Platform.get().currentScreen() == current;
    }

}
