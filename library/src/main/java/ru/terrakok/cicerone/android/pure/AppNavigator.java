package ru.terrakok.cicerone.android.pure;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.os.Bundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.terrakok.cicerone.Navigator;
import ru.terrakok.cicerone.commands.*;

import java.util.LinkedList;

/**
 * Navigator implementation for launch fragments and activities.<br>
 * Feature {@link BackTo} works only for fragments.<br>
 * Recommendation: most useful for Single-Activity application.
 */
public class AppNavigator implements Navigator {

    protected final Activity activity;
    protected final FragmentManager fragmentManager;
    protected final int containerId;
    protected LinkedList<String> localStackCopy;

    public AppNavigator(@NotNull Activity activity, int containerId) {
        this(activity, activity.getFragmentManager(), containerId);
    }

    public AppNavigator(@NotNull Activity activity, @NotNull FragmentManager fragmentManager, int containerId) {
        this.activity = activity;
        this.fragmentManager = fragmentManager;
        this.containerId = containerId;
    }

    @Override
    public void applyCommands(@NotNull Command[] commands) {
        fragmentManager.executePendingTransactions();

        //copy stack before apply commands
        copyStackToLocal();

        for (Command command : commands) {
            try {
                applyCommand(command);
            } catch (RuntimeException e) {
                errorOnApplyCommand(command, e);
            }
        }
    }

    private void copyStackToLocal() {
        localStackCopy = new LinkedList<>();

        final int stackSize = fragmentManager.getBackStackEntryCount();
        for (int i = 0; i < stackSize; i++) {
            localStackCopy.add(fragmentManager.getBackStackEntryAt(i).getName());
        }
    }

    /**
     * Perform transition described by the navigation command
     *
     * @param command the navigation command to apply
     */
    protected void applyCommand(@NotNull Command command) {
        if (command instanceof Forward) {
            activityForward((Forward) command);
        } else if (command instanceof Replace) {
            activityReplace((Replace) command);
        } else if (command instanceof BackTo) {
            backTo((BackTo) command);
        } else if (command instanceof Back) {
            fragmentBack();
        }
    }


    protected void activityForward(@NotNull Forward command) {
        AppScreen screen = (AppScreen) command.getScreen();
        Intent activityIntent = screen.getActivityIntent(activity);

        // Start activity
        if (activityIntent != null) {
            Bundle options = createStartActivityOptions(command, activityIntent);
            checkAndStartActivity(screen, activityIntent, options);
        } else {
            fragmentForward(command);
        }
    }

    protected void fragmentForward(@NotNull Forward command) {
        AppScreen screen = (AppScreen) command.getScreen();
        Fragment fragment = createFragment(screen);
        String screenKey = screen.getScreenKey();

        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

        setupFragmentTransaction(
                command,
                fragmentManager.findFragmentById(containerId),
                fragment,
                fragmentTransaction
        );

        fragmentTransaction
                .replace(containerId, fragment, screenKey)
                .addToBackStack(screenKey)
                .commit();
        localStackCopy.add(screenKey);
    }

    protected void fragmentBack() {
        if (localStackCopy.size() > 0) {
            fragmentManager.popBackStack();
            localStackCopy.removeLast();
        } else {
            activityBack();
        }
    }

    protected void activityBack() {
        activity.finish();
    }

    protected void activityReplace(@NotNull Replace command) {
        AppScreen screen = (AppScreen) command.getScreen();
        Intent activityIntent = screen.getActivityIntent(activity);

        // Replace activity
        if (activityIntent != null) {
            Bundle options = createStartActivityOptions(command, activityIntent);
            checkAndStartActivity(screen, activityIntent, options);
            activity.finish();
        } else {
            fragmentReplace(command);
        }
    }

    protected void fragmentReplace(@NotNull Replace command) {
        AppScreen screen = (AppScreen) command.getScreen();
        Fragment fragment = createFragment(screen);
        String screenKey = screen.getScreenKey();

        if (localStackCopy.size() > 0) {
            fragmentManager.popBackStack();
            localStackCopy.removeLast();

            FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

            setupFragmentTransaction(
                    command,
                    fragmentManager.findFragmentById(containerId),
                    fragment,
                    fragmentTransaction
            );

            fragmentTransaction
                    .replace(containerId, fragment, screenKey)
                    .addToBackStack(screenKey)
                    .commit();
            localStackCopy.add(screenKey);

        } else {
            FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

            setupFragmentTransaction(
                    command,
                    fragmentManager.findFragmentById(containerId),
                    fragment,
                    fragmentTransaction
            );

            fragmentTransaction
                    .replace(containerId, fragment, screenKey)
                    .commit();
        }
    }

    /**
     * Performs {@link BackTo} command transition
     */
    protected void backTo(@NotNull BackTo command) {
        if (command.getScreen() == null) {
            backToRoot();
        } else {
            String key = command.getScreen().getScreenKey();
            int index = localStackCopy.indexOf(key);
            int size = localStackCopy.size();

            if (index != -1) {
                for (int i = 1; i < size - index; i++) {
                    localStackCopy.removeLast();
                }
                fragmentManager.popBackStack(key, 0);
            } else {
                backToUnexisting((AppScreen) command.getScreen());
            }
        }
    }

    private void backToRoot() {
        fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        localStackCopy.clear();
    }

    /**
     * Override this method to setup fragment transaction {@link FragmentTransaction}.
     * For example: setCustomAnimations(...), addSharedElement(...) or setReorderingAllowed(...)
     *
     * @param command             current navigation command. Will be only {@link Forward} or {@link Replace}
     * @param currentFragment     current fragment in container
     *                            (for {@link Replace} command it will be screen previous in new chain, NOT replaced screen)
     * @param nextFragment        next screen fragment
     * @param fragmentTransaction fragment transaction
     */
    protected void setupFragmentTransaction(@NotNull Command command,
                                            @Nullable Fragment currentFragment,
                                            @Nullable Fragment nextFragment,
                                            @NotNull FragmentTransaction fragmentTransaction) {
    }

    /**
     * Override this method to create option for start activity
     *
     * @param command        current navigation command. Will be only {@link Forward} or {@link Replace}
     * @param activityIntent activity intent
     * @return transition options
     */
    @Nullable
    protected Bundle createStartActivityOptions(@NotNull Command command, @NotNull Intent activityIntent) {
        return null;
    }

    private void checkAndStartActivity(@NotNull AppScreen screen,
                                       @NotNull Intent activityIntent,
                                       @Nullable Bundle options) {
        // Check if we can start activity
        if (activityIntent.resolveActivity(activity.getPackageManager()) != null) {
            activity.startActivity(activityIntent, options);
        } else {
            unexistingActivity(screen, activityIntent);
        }
    }

    /**
     * Called when there is no activity to open {@code screenKey}.
     *
     * @param screen         screen
     * @param activityIntent intent passed to start Activity for the {@code screenKey}
     */
    protected void unexistingActivity(@NotNull AppScreen screen, @NotNull Intent activityIntent) {
        // Do nothing by default
    }

    /**
     * Creates Fragment matching {@code screenKey}.
     *
     * @param screen screen
     * @return instantiated fragment for the passed screen
     */
    @Nullable
    protected Fragment createFragment(@NotNull AppScreen screen) {
        Fragment fragment = screen.getFragment();

        if (fragment == null) {
            errorWhileCreatingScreen(screen);
            throw new RuntimeException("Can't create a screen: " + screen.getScreenKey());
        }
        return fragment;
    }

    /**
     * Called when we tried to fragmentBack to some specific screen (via {@link BackTo} command),
     * but didn't found it.
     *
     * @param screen screen
     */
    protected void backToUnexisting(@NotNull AppScreen screen) {
        backToRoot();
    }

    /**
     * Called when we tried to create new intent or fragment but didn't receive them.
     *
     * @param screen screen
     */
    protected void errorWhileCreatingScreen(@NotNull AppScreen screen) {
        // Do nothing by default
    }

    /**
     * Override this method if you want to handle apply command error.
     *
     * @param command command
     * @param error error
     */
    protected void errorOnApplyCommand(
            @NotNull Command command,
            @NotNull RuntimeException error
    ) {
        throw error;
    }
}
