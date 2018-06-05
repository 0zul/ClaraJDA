package com.avairebot.database.controllers;

import com.avairebot.AvaIre;
import com.avairebot.Constants;
import com.avairebot.database.transformers.PlayerTransformer;
import com.avairebot.utilities.CacheUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.User;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

public class PlayerController {

    public static final Cache<Object, Object> cache = CacheBuilder.newBuilder()
        .recordStats()
        .expireAfterAccess(2, TimeUnit.MINUTES)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();

    private static final String[] REQUIRED_PLAYER_ITEMS = new String[]{
        "username", "discriminator", "avatar", "experience"
    };

    @CheckReturnValue
    public static PlayerTransformer fetchPlayer(AvaIre avaire, Message message) {
        return fetchPlayer(avaire, message, message.getAuthor());
    }

    @CheckReturnValue
    public static PlayerTransformer fetchPlayer(AvaIre avaire, Message message, User user) {
        if (!message.getChannelType().isGuild()) {
            return null;
        }

        return (PlayerTransformer) CacheUtil.getUncheckedUnwrapped(cache, asKey(message.getGuild(), user), () -> {
            try {
                PlayerTransformer transformer = new PlayerTransformer(avaire.getDatabase()
                    .newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                    .select(REQUIRED_PLAYER_ITEMS)
                    .where("user_id", user.getId())
                    .andWhere("guild_id", message.getGuild().getId())
                    .get().first());

                if (!transformer.hasData()) {
                    transformer.incrementExperienceBy(100);
                    transformer.setUsername(user.getName());
                    transformer.setDiscriminator(user.getDiscriminator());
                    transformer.setAvatar(user.getAvatarId());

                    avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                        .insert(statement -> {
                            statement.set("guild_id", message.getGuild().getId())
                                .set("user_id", user.getId())
                                .set("username", user.getName(), true)
                                .set("discriminator", user.getDiscriminator())
                                .set("avatar", user.getAvatarId())
                                .set("experience", 100);
                        });

                    return transformer;
                }

                if (isChanged(user, transformer)) {
                    transformer.setUsername(user.getName());
                    transformer.setDiscriminator(user.getDiscriminator());
                    transformer.setAvatar(user.getAvatarId());

                    updateUserData(avaire, user);

                    return transformer;
                }

                // If the users name haven't been encoded yet, we'll do it below.
                String username = transformer.getUsernameRaw();
                if (username.startsWith("base64:")) {
                    return transformer;
                }

                avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                    .useAsync(true)
                    .where("user_id", message.getAuthor().getId())
                    .update(statement -> statement.set("username", message.getAuthor().getName(), true));

                return transformer;
            } catch (SQLException ex) {
                AvaIre.getLogger().error(ex.getMessage(), ex);
                return null;
            }
        });
    }

    public static void updateUserData(AvaIre avaire, User user) {
        try {
            avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                .useAsync(true)
                .where("user_id", user.getId())
                .update(statement -> {
                    statement.set("username", user.getName(), true);
                    statement.set("discriminator", user.getDiscriminator());
                    statement.set("avatar", user.getAvatarId());
                });
        } catch (SQLException e) {
            AvaIre.getLogger().error("Failed to update user with an ID of " + user.getId(), e);
        }
    }

    private static boolean isChanged(User user, PlayerTransformer transformer) {
        return !user.equals(transformer)
            || !user.getDiscriminator().equals(transformer.getDiscriminator())
            || !user.getAvatarId().equals(transformer.getAvatar());
    }

    private static String asKey(@Nonnull Guild guild, @Nonnull User user) {
        return guild.getId() + ":" + user.getId();
    }
}
