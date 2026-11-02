package com.andmx.agent.permission

import java.net.URI

object WebFetchPreapproved {
    private val hosts = setOf(
        "modelcontextprotocol.io",
        "agentskills.io",
        "docs.python.org",
        "en.cppreference.com",
        "docs.oracle.com",
        "learn.microsoft.com",
        "developer.mozilla.org",
        "go.dev",
        "pkg.go.dev",
        "www.php.net",
        "docs.swift.org",
        "kotlinlang.org",
        "ruby-doc.org",
        "doc.rust-lang.org",
        "www.typescriptlang.org",
        "react.dev",
        "angular.io",
        "vuejs.org",
        "nextjs.org",
        "expressjs.com",
        "nodejs.org",
        "bun.sh",
        "jquery.com",
        "getbootstrap.com",
        "tailwindcss.com",
        "d3js.org",
        "threejs.org",
        "redux.js.org",
        "webpack.js.org",
        "jestjs.io",
        "reactrouter.com",
        "docs.djangoproject.com",
        "flask.palletsprojects.com",
        "fastapi.tiangolo.com",
        "pandas.pydata.org",
        "numpy.org",
        "www.tensorflow.org",
        "pytorch.org",
        "scikit-learn.org",
        "matplotlib.org",
        "requests.readthedocs.io",
        "jupyter.org",
        "laravel.com",
        "symfony.com",
        "docs.spring.io",
        "hibernate.org",
        "tomcat.apache.org",
        "gradle.org",
        "maven.apache.org",
        "asp.net",
        "dotnet.microsoft.com",
        "blazor.net",
        "reactnative.dev",
        "docs.flutter.dev",
        "developer.apple.com",
        "developer.android.com",
        "keras.io",
        "spark.apache.org",
        "www.mongodb.com",
        "redis.io",
        "www.postgresql.org",
        "dev.mysql.com",
        "www.sqlite.org",
        "graphql.org",
        "prisma.io",
        "docs.getdbt.com",
        "docs.aws.amazon.com",
        "cloud.google.com",
        "kubernetes.io",
        "www.docker.com",
        "www.terraform.io",
        "www.ansible.com",
        "docs.stripe.com",
        "docs.netlify.com",
        "devcenter.heroku.com",
        "cypress.io",
        "selenium.dev",
        "docs.unity.com",
        "docs.unrealengine.com",
        "git-scm.com",
        "nginx.org",
        "httpd.apache.org",
    )

    private val pathPrefixes = mapOf(
        "wordpress.org" to listOf("/documentation"),
        "huggingface.co" to listOf("/docs"),
        "www.kaggle.com" to listOf("/docs"),
        "vercel.com" to listOf("/docs"),
    )

    private val encodedDotOrSlash = Regex("%(25)*(2f|5c|2e)", RegexOption.IGNORE_CASE)

    fun isPreapprovedUrl(value: String): Boolean {
        val url = runCatching { URI(value) }.getOrNull() ?: return false
        val host = url.host ?: return false
        if (host in hosts) return true
        val prefixes = pathPrefixes[host] ?: return false
        if (containsEncodedDotOrSlash(value)) return false
        val path = url.rawPath ?: url.path ?: return false
        return prefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") }
    }

    fun containsEncodedDotOrSlash(value: String): Boolean {
        val url = runCatching { URI(value) }.getOrNull() ?: return false
        val path = url.rawPath ?: url.path ?: return false
        return encodedDotOrSlash.containsMatchIn(path)
    }

    fun domainSubject(url: String): String? {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')
        return if (host.isNullOrEmpty()) null else "domain:$host"
    }
}
