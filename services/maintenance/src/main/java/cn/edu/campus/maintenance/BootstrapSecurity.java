package cn.edu.campus.maintenance;

import cn.edu.campus.security.ApiAccessRule;
import cn.edu.campus.security.CampusHttpSecurity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** G6 assembly: module owners register explicit action gates and enforce their own object scopes. */
@Configuration
class BootstrapSecurity {
    @Bean
    SecurityFilterChain bootstrapFilterChain(HttpSecurity http,Environment environment,ObjectProvider<ApiAccessRule> rules) throws Exception {
        return CampusHttpSecurity.build(http,environment,rules.orderedStream().toList(),"/api/m3/");
    }
}
