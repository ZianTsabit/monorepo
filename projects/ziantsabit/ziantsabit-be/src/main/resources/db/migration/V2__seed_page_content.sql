-- The CV and About pages as they shipped, transcribed by Django's migration
-- 0014 from the arrays that used to be hardcoded in CV.tsx and About.tsx.
--
-- ON CONFLICT DO NOTHING, so this only ever fills a database that has no page
-- rows yet. The production database ran 0014 long ago, and whatever the owner
-- has saved to either page since must not be overwritten.
--
-- The one `\$` in the CV is deliberate: the SPA's Markdown renderer reads a bare
-- `$` as the start of an inline equation.
INSERT INTO myapp_pagecontent (key, data, updated_at) VALUES
('cv', $seed$
{
  "name": "Ghazian Tsabit Alkamil",
  "location": "Jakarta, Indonesia",
  "links": [
    {
      "label": "LinkedIn",
      "url": "https://www.linkedin.com/in/ghaziantsabitalkamil/",
      "icon_url": "https://cdn.jsdelivr.net/gh/devicons/devicon/icons/linkedin/linkedin-original.svg"
    },
    {
      "label": "GitHub",
      "url": "https://github.com/ZianTsabit",
      "icon_url": "https://cdn.jsdelivr.net/gh/devicons/devicon/icons/github/github-original.svg"
    },
    {
      "label": "✉️ Email",
      "url": "mailto:tsabitghazian@gmail.com",
      "icon_url": ""
    }
  ],
  "summary": {
    "heading": "📄 Summary",
    "body": "Data Engineer with experience architecting high-throughput data platforms across GCP, Azure, and on-premise environments. Proven track record of leading event tracking services handling ~2,500 RPS and ~500 GB of daily data, achieving significant cloud cost reductions of ~30% for BigQuery and ~35% for Dataflow and Pub/Sub. Expertise spans the full data lifecycle, including implementing Medallion Architecture, orchestrating 800+ DBT models with Apache Airflow, and deploying AI-driven RAG pipelines using Gemma 3. Proficient in building scalable infrastructure and implementing observability infrastructure."
  },
  "experience": {
    "heading": "💼 Experience",
    "entries": [
      {
        "title": "Software Engineer - Data",
        "subtitle": "Cermati Fintech Group",
        "subtitle_link": "https://www.cermati.group/",
        "location": "Jakarta, Indonesia",
        "duration": "June 2025 - Present",
        "blurb": "Cermati Fintech Group (CFG) is a fintech company founded in 2015, consisting of five entities. I am part of the Data Platform Team, supporting all entities under CFG (officially under the Indodana entity).",
        "points": [
          "Owned a high-scale event tracking service adopted by the engineering team across the group company, managing ~2,500 RPS and ~500 GB of daily throughput. Redesigned data pipelines to slash Pub/Sub and Dataflow costs by ~35% (yielding ~\\$2,000 USD in monthly savings) and implemented a strict data retention strategy of the event's BigQuery table that successfully optimized the BigQuery costs by ~30% for several events.",
          "Implemented Snowflake ID generation inside that same event tracking service to eliminate event ID collisions, improving data integrity at scale.",
          "Maintained and enhanced a large-scale DBT project consisting of 800+ models, streamlined through the orchestration of 186+ Apache Airflow DAGs to assist the Business Intelligence team building and architecting the group company data warehouse and data mart.",
          "Maintained and managed group company-wide Apache Airflow infrastructure and successfully solved a critical memory leak in the Airflow Triggerer component thus removing the whole on-call routine related to that case."
        ]
      },
      {
        "title": "Data Engineer (Infrastructure)",
        "subtitle": "Intiva",
        "subtitle_link": "https://intiva.id/",
        "location": "Jakarta, Indonesia",
        "duration": "Sept 2024 - June 2025",
        "blurb": "Intiva is an IT consulting and services company specializing in software development, automation, machine learning, and big data analytics.",
        "points": [
          "Engineered the [Bamtren](https://bamtren.com/) MVP, a news analytics platform using Gemma 3 for RAG-driven content generation and sentiment analysis, processing hundreds of thousands of daily messages via a robust pipeline of Airflow, MongoDB, RabbitMQ, and Celery.",
          "Built internal LLM infrastructure and FastAPI services utilized by 5+ engineers and data scientists, integrating Ollama and LangChain while implementing a full-stack monitoring suite (Grafana, Prometheus, Loki) to track on-premise performance.",
          "Standardized DevOps and security protocols by establishing monorepo CI/CD pipelines and implementing HashiCorp Vault secret management, successfully adopted across two production projects to enhance deployment security and efficiency."
        ]
      },
      {
        "title": "Data Governance (Intern)",
        "subtitle": "Sinar Mas Land",
        "subtitle_link": "https://www.sinarmasland.com/",
        "location": "Tangerang, Indonesia",
        "duration": "April - July 2024",
        "blurb": "Sinar Mas Land is one of Indonesia's largest real estate developers, part of the Sinarmas Group conglomerate.",
        "points": [
          "Designed and managed metadata-driven ingestion pipelines to the Bronze layer in Medallion Architecture using Microsoft Azure Data Fabric, streamlining the integration of diverse data sources into a centralized environment."
        ]
      }
    ]
  },
  "projects": {
    "heading": "🛠️ Projects",
    "entries": [
      {
        "title": "HomeLab Infrastructure Project",
        "subtitle": "",
        "subtitle_link": "",
        "location": "",
        "duration": "Oct 2025 - Present",
        "blurb": "",
        "points": [
          "Constructed self-hosted Kubernetes cluster on Proxmox virtualization, configured a multi-node architecture (1 control plane, 2 worker nodes) to master service orchestration and infrastructure management, also implemented observability across multi-node and across the homelab using Grafana and Prometheus, providing real-time monitoring and health metrics for the entire cluster lifecycle.",
          "Hosts this website: the site you are reading runs on that homelab rather than on a managed platform, with its frontend, Django API, PostgreSQL and object storage deployed as Docker Compose stacks on a Proxmox VM and reached only through a Cloudflare Zero Trust tunnel — no port forwarding, no reverse proxy, no certificate to renew."
        ]
      }
    ]
  },
  "skills": {
    "heading": "⚙️ Skills",
    "groups": [
      {
        "label": "Programing Language",
        "items": [
          "Python",
          "Java",
          "JavaScript"
        ]
      },
      {
        "label": "Data Engineering & Orchestration",
        "items": [
          "Apache Airflow",
          "DBT",
          "Apache Beam"
        ]
      },
      {
        "label": "Database & Data Platforms",
        "items": [
          "PostgreSQL",
          "Google BigQuery",
          "MongoDB",
          "Redis",
          "Elasticsearch",
          "Redash"
        ]
      },
      {
        "label": "Cloud & Infrastructure",
        "items": [
          "Google Cloud Platform",
          "Microsoft Azure",
          "Docker",
          "Kubernetes"
        ]
      },
      {
        "label": "Messaging & Streaming",
        "items": [
          "RabbitMQ",
          "Google Pub/Sub"
        ]
      },
      {
        "label": "Monitoring, Logging, & Observability",
        "items": [
          "Grafana",
          "Prometheus",
          "Loki",
          "Promtail"
        ]
      },
      {
        "label": "Security & DevOps",
        "items": [
          "Keycloak",
          "HashiCorp Vault",
          "GitLab CI",
          "GitLab Runner"
        ]
      },
      {
        "label": "Machine Learning & LLM",
        "items": [
          "LangChain",
          "Ollama",
          "Langfuse"
        ]
      }
    ]
  },
  "education": {
    "heading": "🎓 Education & Certifications",
    "entries": [
      {
        "title": "B.Sc. Computer Science",
        "subtitle": "Bandung Institute of Technology",
        "subtitle_link": "https://stei.itb.ac.id/",
        "location": "",
        "duration": "",
        "blurb": "",
        "points": [
          "CGPA: 3.54 / 4.00",
          "Thesis: Development of a Transformation Mechanism from Document-Oriented NoSQL Database to Relational Database."
        ]
      },
      {
        "title": "Associate Cloud Engineer",
        "subtitle": "Google Cloud Platform",
        "subtitle_link": "",
        "location": "",
        "duration": "2024 - 2027",
        "blurb": "",
        "points": []
      }
    ]
  }
}
$seed$::jsonb, now()),
('about', $seed$
{
  "name": "Ghazian Tsabit Alkamil",
  "headline": "Software Engineer — Data Platform",
  "location": "Jakarta, Indonesia",
  "photo_front": "/pp-github.png",
  "photo_back": "/professional-photo.jpeg",
  "photo_alt": "Ghazian Tsabit Alkamil",
  "sections": [
    {
      "heading": "👋 About Me",
      "body": "Hi, I’m Ghazian Tsabit Alkamil, living in Jakarta, Indonesia. I work as a Software Engineer on the Data Platform team at [Cermati Fintech Group](https://www.cermati.group/). I studied Computer Science at the [School of Electrical Engineering and Informatics](https://stei.itb.ac.id/), Bandung Institute of Technology. I have a strong passion for data, software, and infrastructure engineering, and I enjoy exploring how these areas connect and support each other. This site is where I put myself on the internet: I share my projects here, and write about the things that I find interesting."
    },
    {
      "heading": "🎧 Outside of Work",
      "body": "I love spending time with books—especially Indonesian novels, with Eka Kurniawan as my favorite author—watching movies, and swimming, which I usually do about four times a week. Music is also a big part of my life, and I’m a huge fan of The Beatles and Bob Dylan. I enjoy learning new things, and recently I’ve started learning to play the guitar, inspired by the anime *Bocchi the Rock!*"
    }
  ]
}
$seed$::jsonb, now())
ON CONFLICT (key) DO NOTHING;
