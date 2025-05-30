plugins {
    alias(libs.plugins.lombok)
    alias(libs.plugins.quarkus)
}

dependencies {
    api(project(":transactionoutbox-core"))
    api(platform(libs.jakarta.platform.jakarta.jakartaee.bom))
    api(libs.jakarta.enterprise.jakarta.enterprise.cdi.api)
    api(libs.jakarta.transaction.jakarta.transaction.api)
    
    compileOnly(libs.org.projectlombok.lombok)
    
    testImplementation(platform(libs.org.junit.bom))
    testImplementation(platform(libs.io.quarkus.quarkus.bom))
    testImplementation(libs.io.quarkus.quarkus.agroal)
    testImplementation(libs.io.quarkus.quarkus.arc)
    testImplementation(libs.io.quarkus.quarkus.jdbc.h2)
    testImplementation(libs.io.quarkus.quarkus.junit5)
    testImplementation(libs.io.quarkus.quarkus.resteasy)
    testImplementation(libs.io.quarkus.quarkus.undertow)
}

description = "Transaction Outbox Quarkus"
