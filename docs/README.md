This README gives an overview of how to build and contribute to the documentation of Flink Agents.

The documentation is included with the source of Flink Agents in order to ensure that you always have docs corresponding to your checked-out version.

# Requirements

### Build the documentation and serve it locally

The Flink Agents documentation uses [Hugo](https://gohugo.io/getting-started/installing/) to generate HTML files.  More specifically, it uses the *extended version* of Hugo with Sass/SCSS support. 

To build the documentation, you can install Hugo locally or use a Docker image. 

Both methods require you to execute commands in the directory of this module (`docs/`). The built site is served at http://localhost:1313/.

#### Using Hugo Docker image:

```sh
$ git submodule update --init --recursive
$ docker pull jakejarvis/hugo-extended:latest
$ docker run -v $(pwd):/src -p 1313:1313 jakejarvis/hugo-extended:latest server --buildDrafts --buildFuture --bind 0.0.0.0
```

#### Local Hugo installation:

Make sure you have installed [Hugo](https://gohugo.io/getting-started/installing/) on your system.

```sh
$ ./setup_hugo.sh
```

Then build the docs from source:

```sh
$ ./build_docs.sh
```

# Contribute

## Markdown

The documentation pages are written in [Markdown](http://daringfireball.net/projects/markdown/syntax). It is possible to use [GitHub flavored syntax](http://github.github.com/github-flavored-markdown) and intermix plain html.

## Front matter

In addition to Markdown, every page contains a Jekyll front matter, which specifies the title of the page and the layout to use. The title is used as the top-level heading for the page. The default layout is `plain` (found in `_layouts`).

    ---
    title: "Title of the Page"
    ---
    
    ---
    title: "Title of the Page" <-- Title rendered in the side nave
    weight: 1 <-- Weight controls the ordering of pages in the side nav. 
    type: docs <-- required
    aliases:  <-- Alias to setup redirect from removed page to this one
      - /alias/to/removed/page.html
    ---

## Structure

### Page

#### Headings

All documents are structured with headings. From these headings, you can automatically generate a page table of contents (see below).

```
# Level-1 Heading  <- Used for the title of the page 
## Level-2 Heading <- Start with this one for content
### Level-3 heading
#### Level-4 heading
##### Level-5 heading
```

Please stick to the "logical order" when using the headlines, e.g. start with level-2 headings and use level-3 headings for subsections, etc. Don't use a different ordering, because you don't like how a headline looks.

#### Table of Contents

Table of contents are added automatically to every page, based on heading levels 2 - 4. 
The ToC can be omitted by adding the following to the front matter of the page:

    ---
    bookToc: false
    ---

### ShortCodes 

Flink Agents uses [shortcodes](https://gohugo.io/content-management/shortcodes/) to add custom functionality
to its documentation markdown. For example:

    {{< version >}}

Interpolates the current Flink Agents version.

Its implementation and documentation can be found at ./layouts/shortcodes/version.html. Please refer to ./layouts/shortcodes/ for other shortcodes available.