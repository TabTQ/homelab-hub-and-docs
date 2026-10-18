package com.homelabhub.docs.portfolio;

import static com.homelabhub.docs.portfolio.ResourceSpec.Field;
import static com.homelabhub.docs.portfolio.ResourceSpec.Type;
import static com.homelabhub.docs.portfolio.ResourceSpec.Usage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The editable content types (plan 8.3). Adding a field or a type is one entry here. */
public final class PortfolioResources {

    private PortfolioResources() {}

    public static final Map<String, ResourceSpec> ALL = build();

    private static Map<String, ResourceSpec> build() {
        Map<String, ResourceSpec> m = new LinkedHashMap<>();
        add(m, new ResourceSpec("profile", "Profile", "profile", List.of(
                Field.text("name", "name", "Name", true, false, 100),
                Field.text("title", "title", "Title", true, false, 150),
                Field.text("tagline", "tagline", "Tagline", false, false, 300),
                Field.text("location", "location", "Location", false, false, 150),
                Field.text("email", "email", "Email", false, false, 200),
                Field.text("phone", "phone", "Phone", false, false, 50),
                Field.text("brand", "brand", "Brand", false, false, 100),
                Field.text("status", "status", "Status", false, false, 100),
                Field.longText("summary", "summary", "Summary", false, false),
                Field.list("bio", "bio", "About paragraphs", "One paragraph per entry"),
                Field.jsonObject("socials", "socials", "Social links", "{\"github\": \"https://...\", \"githubLabel\": \"...\", ...}"),
                Field.jsonList("stats", "stats", "Headline numbers", "[{\"value\": \"4+\", \"label\": \"Years\", \"sub\": \"...\"}]")),
                "id", true, null, null, null, null, null, null, true, List.of()));

        add(m, new ResourceSpec("experiences", "Experience", "experiences", List.of(
                Field.slug("slug", "slug", "Slug"),
                Field.text("role", "role", "Role", true, false, 200),
                Field.text("company", "company", "Company", true, false, 200),
                Field.text("client", "client", "Client", false, true, 200),
                Field.text("duration", "duration_label", "Duration", true, false, 100),
                Field.text("location", "location", "Location", false, true, 150),
                Field.longText("summary", "summary", "Summary", true, false),
                Field.list("highlights", "highlights", "Highlights", "One bullet per entry"),
                Field.sortOrder()),
                "sort_order, id", false, null, null, null, "experience_skills", "experience_id", "experience", true, List.of()));

        add(m, new ResourceSpec("projects", "Projects", "projects", List.of(
                Field.slug("slug", "slug", "Slug"),
                Field.choice("kind", "kind", "Kind", true, "flagship", "small"),
                Field.text("title", "title", "Title", true, false, 200),
                Field.longText("description", "description", "Description", true, false),
                Field.url("repoUrl", "repo_url", "Repository URL"),
                Field.url("liveUrl", "live_url", "Live URL"),
                Field.text("type", "type", "Type", false, true, 100),
                Field.sortOrder()),
                "sort_order, id", false, null, null, null, "project_skills", "project_id", "project", true,
                List.of(new Usage("deliverables", "SELECT count(*) FROM project_deliverables WHERE project_id = ?"))));

        add(m, new ResourceSpec("project-case-studies", "Case study", "project_case_studies", List.of(
                Field.text("client", "client", "Client", false, true, 200),
                Field.text("role", "role", "Role", false, true, 200),
                Field.text("duration", "duration", "Duration", false, true, 100),
                Field.longText("context", "context", "Context", false, true),
                Field.list("platform", "platform", "Platform skills", "Names of skills used"),
                Field.jsonList("metrics", "metrics", "Before / after metrics", "[{\"label\": \"...\", \"before\": \"...\", \"after\": \"...\", \"emphasis\": \"accent\"}]"),
                Field.jsonList("layers", "layers", "Layers", null),
                Field.jsonList("techStack", "tech_stack", "Tech stack", "[{\"tech\": \"...\", \"layer\": \"...\"}]")),
                "id", false, "projectId", "project_id", "projects", null, null, null, false, List.of()));

        add(m, new ResourceSpec("project-deliverables", "Deliverables", "project_deliverables", List.of(
                Field.slug("key", "deliverable_key", "Key"),
                Field.text("title", "title", "Title", true, false, 200),
                Field.text("tag", "tag", "Tag", true, false, 100),
                Field.longText("body", "body", "Body", true, false),
                Field.sortOrder()),
                "sort_order, id", false, "projectId", "project_id", "projects", null, null, null, false, List.of()));

        add(m, new ResourceSpec("education", "Education", "academic_items", List.of(
                Field.choice("type", "type", "Type", true, "Degree", "Publication", "Certification"),
                Field.text("title", "title", "Title", true, false, 300),
                Field.text("institution", "institution", "Institution", true, false, 300),
                Field.text("date", "date_label", "Date", true, false, 100),
                Field.longText("description", "description", "Description", false, true),
                Field.url("url", "url", "URL"),
                Field.sortOrder()),
                "sort_order, id", false, null, null, null, null, null, null, false, List.of()));

        add(m, new ResourceSpec("skill-groups", "Skill groups", "skill_groups", List.of(
                Field.text("category", "category", "Name", true, false, 100),
                Field.text("icon", "icon", "Icon", false, false, 60),
                Field.sortOrder()),
                "sort_order, id", false, null, null, null, null, null, null, false,
                List.of(new Usage("skills", "SELECT count(*) FROM skills WHERE group_id = ?"))));

        add(m, new ResourceSpec("skills", "Skills", "skills", List.of(
                Field.text("label", "label", "Label", true, false, 100),
                Field.sortOrder()),
                "sort_order, id", false, "groupId", "group_id", "skill-groups", null, null, null, false, List.of(
                        new Usage("roles", "SELECT count(*) FROM experience_skills WHERE skill_id = ?"),
                        new Usage("projects", "SELECT count(*) FROM project_skills WHERE skill_id = ?"),
                        new Usage("homelab devices", "SELECT count(*) FROM device_skills WHERE skill_id = ?"),
                        new Usage("homelab containers", "SELECT count(*) FROM container_skills WHERE skill_id = ?"),
                        new Usage("homelab services", "SELECT count(*) FROM service_skills WHERE skill_id = ?"))));

        add(m, new ResourceSpec("homelab-devices", "Homelab devices", "homelab_devices", List.of(
                Field.slug("key", "device_key", "Key"),
                Field.choice("category", "category", "Category", true, "proxmox", "raspberry_pi", "network"),
                Field.text("name", "name", "Name", true, false, 200),
                Field.longText("summary", "summary", "Summary", false, true),
                Field.text("ipAddress", "ip_address", "IP address", false, true, 100),
                Field.text("status", "status", "Status", false, true, 100),
                Field.jsonList("hardware", "hardware", "Hardware", "[{\"component\": \"CPU\", \"spec\": \"...\"}]"),
                Field.jsonObject("extra", "extra", "Extra", null),
                Field.sortOrder()),
                "sort_order, id", false, null, null, null, "device_skills", "device_id", null, false, List.of(
                        new Usage("containers", "SELECT count(*) FROM homelab_containers WHERE device_id = ?"),
                        new Usage("services", "SELECT count(*) FROM homelab_services WHERE device_id = ?"),
                        new Usage("network entries", "SELECT count(*) FROM homelab_network_entities WHERE device_id = ?"))));

        add(m, new ResourceSpec("homelab-containers", "Homelab containers", "homelab_containers", List.of(
                Field.slug("key", "container_key", "Key"),
                Field.text("name", "name", "Name", true, false, 200),
                Field.text("ipAddress", "ip_address", "IP address", false, true, 100),
                Field.text("purpose", "purpose", "Purpose", false, true, 200),
                Field.text("resources", "resources", "Resources", false, true, 100),
                Field.text("status", "status", "Status", false, false, 50),
                Field.jsonList("services", "services", "Services", null),
                Field.sortOrder()),
                "sort_order, id", false, "deviceId", "device_id", "homelab-devices", "container_skills", "container_id", null, false, List.of()));

        add(m, new ResourceSpec("homelab-services", "Homelab services", "homelab_services", List.of(
                Field.text("serviceName", "service_name", "Name", true, false, 200),
                Field.text("containerName", "container_name", "Container", false, true, 200),
                Field.text("hostPort", "host_port", "Port", false, true, 50),
                Field.text("function", "function", "Function", false, true, 300),
                Field.sortOrder()),
                "sort_order, id", false, "deviceId", "device_id", "homelab-devices", "service_skills", "service_id", null, false, List.of()));

        add(m, new ResourceSpec("homelab-network", "Homelab network", "homelab_network_entities", List.of(
                Field.text("entityType", "entity_type", "Type", true, false, 50),
                Field.text("name", "name", "Name", true, false, 200),
                Field.jsonObject("spec", "spec", "Details", null),
                Field.sortOrder()),
                "sort_order, id", false, "deviceId", "device_id", "homelab-devices", null, null, null, false, List.of()));
        return java.util.Collections.unmodifiableMap(m);
    }

    private static void add(Map<String, ResourceSpec> m, ResourceSpec s) {
        m.put(s.path(), s);
    }

    static boolean isText(Type t) {
        return t == Type.TEXT || t == Type.LONGTEXT || t == Type.SLUG || t == Type.URL || t == Type.ENUM;
    }
}
