package com.avairebot.commands.administration;

import com.avairebot.AvaIre;
import com.avairebot.commands.CommandMessage;
import com.avairebot.contracts.commands.CacheFingerprint;
import com.avairebot.contracts.commands.Command;
import com.avairebot.modlog.ModlogAction;
import com.avairebot.modlog.ModlogModule;
import com.avairebot.modlog.ModlogType;
import com.avairebot.utilities.RoleUtil;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.User;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@CacheFingerprint(name = "kick-command")
public class KickCommand extends Command {

    private static final Pattern userRegEX = Pattern.compile("<@(!|)+[0-9]{16,}+>", Pattern.CASE_INSENSITIVE);

    public KickCommand(AvaIre avaire) {
        super(avaire, false);
    }

    @Override
    public String getName() {
        return "Kick Command";
    }

    @Override
    public String getDescription() {
        return "Kicks the mentioned user from the server with the provided reason, this action will be reported to any channel that has modloging enabled.";
    }

    @Override
    public List<String> getUsageInstructions() {
        return Collections.singletonList("`:command <user> [reason]` - Kicks the mentioned user with the given reason.");
    }

    @Override
    public List<String> getExampleUsage() {
        return Collections.singletonList("`:command @Senither Spamming things`");
    }

    @Override
    public List<String> getTriggers() {
        return Collections.singletonList("kick");
    }

    @Override
    public List<String> getMiddleware() {
        return Arrays.asList(
            "require:user,general.kick_members",
            "throttle:user,1,5"
        );
    }

    @Override
    public boolean onCommand(CommandMessage context, String[] args) {
        if (context.getMentionedUsers().isEmpty() || !userRegEX.matcher(args[0]).matches()) {
            return sendErrorMessage(context, "You must mention the user you want to kick.");
        }

        User user = context.getMentionedUsers().get(0);
        if (userHasHigherRole(user, context.getMember())) {
            return sendErrorMessage(context, "You can't kick people with a higher, or the same role as yourself.");
        }

        return kickUser(context, context.getGuild().getMember(user), args);
    }

    private boolean kickUser(CommandMessage context, Member user, String[] args) {
        String reason = generateMessage(args);

        context.getGuild().getController().kick(user, String.format("%s - %s#%s (%s)",
            reason,
            context.getAuthor().getName(),
            context.getAuthor().getDiscriminator(),
            context.getAuthor().getId()
        )).queue(aVoid -> {
                ModlogModule.log(avaire, context, new ModlogAction(
                        ModlogType.KICK,
                        context.getAuthor(),
                        user.getUser(),
                        reason
                    )
                );

                context.makeSuccess("**:target** was kicked by :user for \":reason\"")
                    .set("target", user.getUser().getName() + "#" + user.getUser().getDiscriminator())
                    .set("reason", reason)
                    .queue();
            }, throwable -> context.makeWarning("Failed to kick **:target** due to an error: :error")
                .set("target", user.getUser().getName() + "#" + user.getUser().getDiscriminator())
                .set("error", throwable.getMessage())
                .queue()
        );
        return true;
    }

    private boolean userHasHigherRole(User user, Member author) {
        Role role = RoleUtil.getHighestFrom(author.getGuild().getMember(user));
        return role != null && RoleUtil.isRoleHierarchyHigher(author.getRoles(), role);
    }

    private String generateMessage(String[] args) {
        return args.length < 2 ?
            "No reason was given." :
            String.join(" ", Arrays.copyOfRange(args, 1, args.length));
    }
}
