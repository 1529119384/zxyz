package uno.acloud.im.infrastructure.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.springframework.stereotype.Component;
import uno.acloud.im.application.UserPresenceService;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImConnectionRegistry {

    private final UserPresenceService userPresenceService;
    private final Map<Long, ConcurrentHashMap<ChannelId, Channel>> userChannels = new ConcurrentHashMap<>();

    public ImConnectionRegistry(UserPresenceService userPresenceService) {
        this.userPresenceService = userPresenceService;
    }

    public int register(Long userId, Channel channel) {
        ConcurrentHashMap<ChannelId, Channel> channels = userChannels.computeIfAbsent(userId, key -> new ConcurrentHashMap<>());
        channels.put(channel.id(), channel);
        int count = channels.size();
        userPresenceService.markOnline(userId, count);
        return count;
    }

    public int unregister(Long userId, Channel channel) {
        final int[] count = {0};
        userChannels.compute(userId, (key, channels) -> {
            if (channels == null) return null;
            channels.remove(channel.id());
            count[0] = channels.size();
            return channels.isEmpty() ? null : channels;
        });
        if (count[0] == 0) {
            userPresenceService.markOffline(userId, LocalDateTime.now());
        } else {
            userPresenceService.updateOnlineCount(userId, count[0]);
        }
        return count[0];
    }

    public int getConnectionCount(Long userId) {
        ConcurrentHashMap<ChannelId, Channel> channels = userChannels.get(userId);
        return channels == null ? 0 : channels.size();
    }

    public List<Channel> listChannels(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userIds.stream()
                .distinct()
                .map(userChannels::get)
                .filter(channels -> channels != null && !channels.isEmpty())
                .flatMap(channels -> channels.values().stream())
                .toList();
    }
}
