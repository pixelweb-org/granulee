package io.granulee.web

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Duration

/** When the jar carries the built frontend, client-side routes fall through to index.html. */
@Controller
class SpaController {
    @GetMapping("/explorer/**", "/tables/**", "/recommendations/**", "/trials/**", "/sources")
    fun spa(): String = "forward:/index.html"
}

/**
 * The frontend's scripts and styles carry a content hash in their names, so they can be cached for good.
 * Everything else that is static (index.html, the favicon) is never cached; see application.yml.
 */
@Configuration
class StaticCaching : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
    }
}
