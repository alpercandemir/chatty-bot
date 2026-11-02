package com.chatty;

import static com.chatty.store.Json.parse;
import static com.chatty.store.Json.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:hosted;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "chatty.key=",
            "chatty.mode=scripted",
            "chatty.live-only=true",
            "chatty.secure-cookies=true",
            "chatty.public-origin=https://chatty.138.2.168.194.sslip.io",
        })
@AutoConfigureMockMvc
class HostedDemoTest {

    @Autowired MockMvc mvc;

    @Test
    void configuredPublicOriginIsAllowedWhileUnknownHostsAndOriginsRemainBlocked()
            throws Exception {
        String host = "chatty.138.2.168.194.sslip.io";
        mvc.perform(
                        post("/api/v1/demo/presenter")
                                .with(
                                        request -> {
                                            request.setServerName(host);
                                            return request;
                                        })
                                .header("Origin", "https://" + host))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/demo/presenter")
                                .with(
                                        request -> {
                                            request.setServerName(host);
                                            return request;
                                        })
                                .header("Origin", "https://" + host + ".evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/health")
                                .with(
                                        request -> {
                                            request.setServerName("unconfigured.example");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void hostedDemoUsesSecureSessionsAndRejectsRehearsalEvenWhenRequestedDirectly()
            throws Exception {
        var bootstrap =
                mvc.perform(post("/api/v1/demo/presenter"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var settings = parse(bootstrap.getContentAsString());
        assertThat(settings.path("liveOnly").asBoolean()).isTrue();
        assertThat(str(settings, "defaultMode")).isEqualTo("live");
        assertThat(settings.has("key")).isFalse();
        String presenterCookie = bootstrap.getHeader("Set-Cookie");
        assertThat(presenterCookie).contains("HttpOnly", "SameSite=Strict", "; Secure");

        var selected =
                mvc.perform(
                                post("/api/v1/demo/sessions")
                                        .cookie(cookie(presenterCookie))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"pack\":\"commerce\",\"principalId\":\"customer-aylin\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        String customerCookie = selected.getHeader("Set-Cookie");
        assertThat(customerCookie).contains("HttpOnly", "SameSite=Strict", "; Secure");

        var rejected =
                mvc.perform(
                                post("/api/v1/conversations")
                                        .cookie(cookie(customerCookie))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"mode\":\"scripted\"}"))
                        .andExpect(status().isUnprocessableEntity())
                        .andReturn()
                        .getResponse();
        assertThat(str(parse(rejected.getContentAsString()).path("error"), "code"))
                .isEqualTo("LIVE_ONLY");

        var conversation =
                mvc.perform(
                                post("/api/v1/conversations")
                                        .cookie(cookie(customerCookie))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(str(parse(conversation.getContentAsString()), "mode")).isEqualTo("live");
    }

    private Cookie cookie(String header) {
        String[] pair = header.split(";", 2)[0].split("=", 2);
        return new Cookie(pair[0], pair[1]);
    }
}
