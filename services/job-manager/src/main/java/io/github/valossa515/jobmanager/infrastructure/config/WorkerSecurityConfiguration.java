
package io.github.valossa515.jobmanager.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
public class WorkerSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain workerRoutes(HttpSecurity http)
            throws Exception {

        return http
                .securityMatcher(
                        "/api/v1/jobs/*/executions/**"
                )
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("WORKER")
                )
                .httpBasic(withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain existingRoutes(HttpSecurity http)
            throws Exception {

        return http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/api/v1/jobs"
                        )
                )
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories
                .createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService workerUsers(
            @Value("${taskmesh.security.java-password:}")
            String javaPassword,

            @Value("${taskmesh.security.dotnet-password:}")
            String dotnetPassword,

            PasswordEncoder encoder
    ) {
        var users = new ArrayList<UserDetails>();

        addWorker(
                users,
                "worker-java-local",
                javaPassword,
                encoder
        );

        addWorker(
                users,
                "worker-dotnet-local",
                dotnetPassword,
                encoder
        );

        return new InMemoryUserDetailsManager(
                users.toArray(UserDetails[]::new)
        );
    }

    private void addWorker(
            ArrayList<UserDetails> users,
            String username,
            String password,
            PasswordEncoder encoder
    ) {
        if (password == null || password.isBlank()) {
            return;
        }

        users.add(
                User.withUsername(username)
                        .password(encoder.encode(password))
                        .roles("WORKER")
                        .build()
        );
    }
}