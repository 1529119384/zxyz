package uno.acloud.team.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.TeamCreatedEvent;
import uno.acloud.common.event.TeamMemberAddedEvent;
import uno.acloud.common.event.TeamMemberRemovedEvent;
import uno.acloud.common.event.TeamUpdatedEvent;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.team.dto.team.CreateTeamMemberRequest;
import uno.acloud.team.dto.team.CreateTeamRequest;
import uno.acloud.team.entity.Team;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 54 号 §2.3 A4：team.member.* / team.created / team.updated 发布端 wire 契约测试。
 *
 * <p>此前 {@code TeamEventPublisherTest} 只断言「发了某路由键的某个字符串」，载荷内容零验证 ——
 * record 加字段/改字段名时发布端与消费端会静默漂移。本类用<b>真实 ObjectMapper</b>
 * （findAndRegisterModules，与生产 Boot 自动配置同向）对发布载荷做<b>真实序列化 → 反序列化往返</b>，
 * 并逐一断言路由 key 与载荷字段。</p>
 *
 * <p>afterCommit 语义（发布时机在事务提交之后）钉在服务层：
 * {@code EnterpriseTeamServiceTest#createMember_publishesMemberAddedEventOnlyAfterCommit}。</p>
 */
@ExtendWith(MockitoExtension.class)
class TeamEventPublisherContractTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private TeamEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        publisher = new TeamEventPublisher(rabbitTemplate, objectMapper);
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setAvatar("avatar.png");
        team.setDescription("desc-" + name);
        team.setOwnerUserId(1L);
        return team;
    }

    private UserInfoDTO owner() {
        UserInfoDTO owner = new UserInfoDTO();
        owner.setId(1L);
        owner.setUsername("admin");
        owner.setName("管理员");
        owner.setEmail("admin@test.com");
        return owner;
    }

    @Test
    void teamCreated_payloadRoundTripsWithCorrectRoutingKey() throws Exception {
        publisher.publishTeamCreated(team(10L, "TestTeam"), owner(), new CreateTeamRequest());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE), eq(RabbitMqConstants.ROUTING_KEY_TEAM_CREATED), payload.capture());

        TeamCreatedEvent event = objectMapper.readValue(payload.getValue(), TeamCreatedEvent.class);
        assertAll("team.created 载荷契约（发布端 ↔ 消费端 wire 一致）",
                () -> assertEquals(RabbitMqConstants.ROUTING_KEY_TEAM_CREATED, event.eventType()),
                () -> assertEquals(1, event.version()),
                () -> assertTrue(event.timestamp() > 0, "timestamp 必须由发布端填充"),
                () -> assertEquals(10L, event.teamId()),
                () -> assertEquals("TestTeam", event.name()),
                () -> assertEquals("avatar.png", event.avatar()),
                () -> assertEquals("desc-TestTeam", event.description()),
                () -> assertEquals(1L, event.ownerUserId()),
                () -> assertEquals("admin", event.ownerUsername()),
                () -> assertEquals("管理员", event.ownerName()),
                () -> assertEquals("admin@test.com", event.ownerEmail()));
    }

    @Test
    void teamUpdated_payloadRoundTripsWithCorrectRoutingKey() throws Exception {
        publisher.publishTeamUpdated(team(11L, "RenamedTeam"), owner());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE), eq(RabbitMqConstants.ROUTING_KEY_TEAM_UPDATED), payload.capture());

        TeamUpdatedEvent event = objectMapper.readValue(payload.getValue(), TeamUpdatedEvent.class);
        assertAll("team.updated 载荷契约",
                () -> assertEquals(RabbitMqConstants.ROUTING_KEY_TEAM_UPDATED, event.eventType()),
                () -> assertEquals(1, event.version()),
                () -> assertTrue(event.timestamp() > 0),
                () -> assertEquals(11L, event.teamId()),
                () -> assertEquals("RenamedTeam", event.name()),
                () -> assertEquals("avatar.png", event.avatar()),
                () -> assertEquals("desc-RenamedTeam", event.description()),
                () -> assertEquals(1L, event.ownerUserId()),
                () -> assertEquals("admin", event.ownerUsername()));
    }

    @Test
    void memberAdded_payloadRoundTripsWithCorrectRoutingKeyAndSequence() throws Exception {
        UserInfoDTO user = new UserInfoDTO();
        user.setId(2L);
        user.setUsername("newuser");
        user.setName("新成员");
        user.setEmail("new@test.com");
        user.setAvatar("u.png");
        CreateTeamMemberRequest request = new CreateTeamMemberRequest();
        request.setRoleCode("team_member");

        publisher.publishMemberCreated(10L, user, request);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE), eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED), payload.capture());

        TeamMemberAddedEvent event = objectMapper.readValue(payload.getValue(), TeamMemberAddedEvent.class);
        assertAll("team.member.added 载荷契约（sequenceNumber 是消费端乱序判据）",
                () -> assertEquals(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED, event.eventType()),
                () -> assertEquals(1, event.version()),
                () -> assertTrue(event.timestamp() > 0),
                () -> assertEquals(10L, event.teamId()),
                () -> assertEquals(2L, event.userId()),
                () -> assertEquals("newuser", event.username()),
                () -> assertEquals("新成员", event.name()),
                () -> assertEquals("new@test.com", event.email()),
                () -> assertEquals("u.png", event.avatar()),
                () -> assertEquals("team_member", event.roleCode()),
                () -> assertTrue(event.sequenceNumber() > 0, "sequenceNumber 必须非零"));
    }

    @Test
    void memberRemoved_payloadRoundTripsWithCorrectRoutingKeyAndSequence() throws Exception {
        publisher.publishMemberRemoved(10L, 2L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE), eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED), payload.capture());

        TeamMemberRemovedEvent event = objectMapper.readValue(payload.getValue(), TeamMemberRemovedEvent.class);
        assertAll("team.member.removed 载荷契约",
                () -> assertEquals(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED, event.eventType()),
                () -> assertEquals(1, event.version()),
                () -> assertTrue(event.timestamp() > 0),
                () -> assertEquals(10L, event.teamId()),
                () -> assertEquals(2L, event.userId()),
                () -> assertTrue(event.sequenceNumber() > 0));
    }
}
