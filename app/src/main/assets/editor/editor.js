/* Native shell integration. CKEditor 5 49.0.0 is bundled under GPL-2.0-or-later. */
'use strict';
(() => {
    const initial = JSON.parse(PocketNative.initialDocument());
    const title = document.getElementById('title');
    const fallback = document.getElementById('fallback');
    const preview = document.getElementById('preview');
    const editorHost = document.getElementById('editor-host');
    title.value = initial.title;
    const isMarkdown = initial.markdown != null;
    let editor = null, ready = false, options = window.pocketOptions || initial.options || {}, lastHtml = initial.html;
    let dirty = false, applyingSource = false, htmlRevision = 0, titleRevision = 0;
    let editorPromise = null, editorLibraryPromise = null, fallbackActive = false;
    let titleFocusPending = !!initial.focusTitle, editingFocusApplied = false;
    const preserved = new Map(), attributes = new Map();
    let marker = 0;
    const token = () => 'pocket-' + (++marker) + '-' + Math.random().toString(36).slice(2);
    const resourceUrl = value => /^data:|^\/_note_resource\?/i.test(value) ? value : '/_note_resource?url=' + encodeURIComponent(value);
    const parsed = html => {
        const doc = document.implementation.createHTMLDocument('');
        const template = doc.createElement('template'); template.innerHTML = html;
        doc.body.append(template.content); return doc;
    };

    function preserveHtmlLineBreaks(doc) {
        // Some HTML notes contain literal line separators within a paragraph.
        // Make those separators explicit before CKEditor's whitespace normalization.
        // Structural HTML indentation and preformatted content keep their own rules.
        const blocks = 'address,article,aside,blockquote,caption,center,dd,details,dialog,dir,div,dl,dt,fieldset,figcaption,figure,' +
            'footer,form,h1,h2,h3,h4,h5,h6,header,hgroup,hr,legend,li,main,menu,nav,ol,p,pre,section,summary,table,tbody,td,tfoot,th,thead,tr,ul';
        const candidates = [doc.body, ...doc.body.querySelectorAll('p,h1,h2,h3,h4,h5,h6,div,li,td,th,blockquote')];
        for (const block of candidates) {
            if (block.querySelector(blocks)) continue;
            const tokens = [], replacements = new Map();
            const walker = doc.createTreeWalker(block, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
            let node;
            while ((node = walker.nextNode())) {
                if (node.nodeType === Node.ELEMENT_NODE) {
                    if (node.localName === 'br') tokens.push({kind:'br'});
                    continue;
                }
                let controlledWhitespace = false;
                for (let parent = node.parentElement; parent; parent = parent.parentElement) {
                    if (parent.matches('pre,textarea') || parent.style.whiteSpace) { controlledWhitespace = true; break; }
                }
                if (controlledWhitespace || !node.data.includes('\n') ||
                    (!node.data.trim() && /[\t ]/.test(node.data))) {
                    if (node.data.trim()) tokens.push({kind:'text'});
                    continue;
                }
                const parts = node.data.split('\n');
                replacements.set(node, parts.map(() => false));
                parts.forEach((part, index) => {
                    if (part.trim()) tokens.push({kind:'text'});
                    if (index < parts.length - 1) tokens.push({kind:'lf', node, index});
                });
            }
            const first = tokens.findIndex(item => item.kind !== 'lf');
            let last = tokens.length - 1;
            while (last >= 0 && tokens[last].kind === 'lf') last--;
            tokens.forEach((item, index) => {
                if (item.kind === 'lf' && index > first && index < last &&
                    tokens[index - 1]?.kind !== 'br' && tokens[index + 1]?.kind !== 'br') {
                    replacements.get(item.node)[item.index] = true;
                }
            });
            replacements.forEach((breaks, text) => {
                if (!breaks.some(Boolean)) return;
                const fragment = doc.createDocumentFragment();
                text.data.split('\n').forEach((part, index) => {
                    if (index) fragment.append(breaks[index - 1] ? doc.createElement('br') : doc.createTextNode('\n'));
                    fragment.append(doc.createTextNode(part));
                });
                text.replaceWith(fragment);
            });
        }
    }

    // Preserve executable/embedded objects as inert tokens. They are restored only in serialized data.
    function forEditing(html, rewriteResources = true, readOnlyPreview = false) {
        const doc = parsed(html);
        [...doc.body.querySelectorAll('*')].filter(node =>
            /^(script|style|iframe|object|embed|link|meta|base|template|noscript|canvas|svg|math)$/i.test(node.tagName) ||
            node instanceof HTMLUnknownElement || node.localName.includes('-')).forEach(node => {
            if (!doc.body.contains(node)) return;
            const id = token(); preserved.set(id, node.outerHTML);
            const replacement = doc.createElement('span');
            replacement.setAttribute('data-pocket-preserved', id);
            const url = node.getAttribute('src') || node.getAttribute('data-url') || '';
            if (/^(?:https?:)?\/\//i.test(url)) {
                replacement.setAttribute('data-pocket-embed-url', url);
                replacement.setAttribute('contenteditable', 'false');
                const strong = doc.createElement('strong');
                strong.textContent = node.getAttribute('title') || 'Встроенный материал';
                const address = doc.createElement('span');
                address.textContent = url;
                replacement.append(strong, doc.createElement('br'), address);
            } else replacement.textContent = 'Встроенный HTML · ' + node.tagName.toLowerCase();
            node.replaceWith(replacement);
        });
        if (!isMarkdown) preserveHtmlLineBreaks(doc);
        doc.body.querySelectorAll('*').forEach(node => {
            // Disable reader controls in the detached document, before autofocus or editable nodes go live.
            if (readOnlyPreview) {
                if (node.matches('input,textarea,select,button')) node.disabled = true;
                if (node.hasAttribute('contenteditable')) node.setAttribute('contenteditable', 'false');
                node.removeAttribute('autofocus');
            }
            const removed = {};
            [...node.attributes].forEach(attr => {
                if (/^on/i.test(attr.name) || attr.name === 'srcdoc' ||
                    (/^(href|src|action|formaction|xlink:href)$/i.test(attr.name) && /^\s*(javascript|vbscript):/i.test(attr.value))) {
                    removed[attr.name] = attr.value; node.removeAttribute(attr.name);
                }
            });
            // Route binary resources through native authenticated transport, preserving original URLs.
            for (const name of ['src','poster']) {
                if (rewriteResources && node.hasAttribute(name) && !/^data:|^\/_note_resource\?/i.test(node.getAttribute(name))) {
                    removed[name] = node.getAttribute(name);
                    node.setAttribute(name, resourceUrl(removed[name]));
                }
            }
            if (node.hasAttribute('srcset')) {
                removed.srcset = node.getAttribute('srcset'); node.removeAttribute('srcset');
            }
            if (Object.keys(removed).length) {
                const id = token(); attributes.set(id, removed); node.setAttribute('data-pocket-attributes', id);
            }
        });
        return doc.body.innerHTML;
    }
    function forSaving(html) {
        const doc = parsed(html);
        doc.body.querySelectorAll('[data-pocket-attributes]').forEach(node => {
            const saved = attributes.get(node.getAttribute('data-pocket-attributes'));
            node.removeAttribute('data-pocket-attributes');
            if (saved) {
                const currentSrc = node.getAttribute('src');
                const sourceChanged = saved.src != null && currentSrc != null && currentSrc !== saved.src && currentSrc !== resourceUrl(saved.src);
                Object.entries(saved).forEach(([name, value]) => {
                    if ((name === 'src' || name === 'poster') && node.hasAttribute(name) &&
                        node.getAttribute(name) !== value && node.getAttribute(name) !== resourceUrl(value)) return;
                    if (name === 'srcset' && sourceChanged) return;
                    node.setAttribute(name, value);
                });
            }
        });
        doc.body.querySelectorAll('[data-pocket-preserved]').forEach(node => {
            const saved = preserved.get(node.getAttribute('data-pocket-preserved'));
            if (saved != null) {
                const template = doc.createElement('template'); template.innerHTML = saved;
                node.replaceWith(template.content);
            }
        });
        return doc.body.innerHTML;
    }
    function forSavingMarkdown(value) {
        // Only restore our marked HTML fragments; surrounding Markdown, code fences and spacing stay source text.
        const marked = /<([a-z][a-z0-9:-]*)\b[^>]*data-pocket-(?:preserved|attributes)=[^>]*>/gi;
        let result = '', position = 0, match;
        while ((match = marked.exec(value))) {
            const tag = match[1], start = match.index;
            let end = marked.lastIndex;
            if (!/^(?:img|br|hr|input|source|meta|link)$/i.test(tag)) {
                const tags = new RegExp('<(\\/?)' + tag + '\\b[^>]*>', 'gi');
                tags.lastIndex = end;
                let depth = 1, nested;
                while (depth && (nested = tags.exec(value))) { depth += nested[1] ? -1 : 1; end = tags.lastIndex; }
                if (depth) continue;
            }
            result += value.slice(position, start) + forSaving(value.slice(start, end));
            position = end; marked.lastIndex = end;
        }
        return result + value.slice(position);
    }
    function sourceArea() { return document.querySelector('.ck-source-editing-area textarea'); }
    function snapshot() {
        if (!ready || applyingSource) return null;
        let html = lastHtml;
        if (!editor && fallbackActive) html = fallback.value;
        else if (dirty) { const source = sourceArea(); const data = source ? source.value : editor.getData(); html = isMarkdown ? forSavingMarkdown(data) : forSaving(data); }
        if (html !== lastHtml) { lastHtml = html; htmlRevision++; }
        return JSON.stringify({html:lastHtml,title:title.value,htmlRevision,titleRevision});
    }
    function flush() {
        const previous = htmlRevision;
        const current = snapshot();
        if (current && previous !== htmlRevision) PocketNative.htmlChanged(lastHtml, htmlRevision);
    }
    function resizeTitle() { title.style.height = 'auto'; title.style.height = title.scrollHeight + 'px'; }
    title.addEventListener('input', () => { resizeTitle(); titleRevision++; PocketNative.titleChanged(title.value, titleRevision); });
    function focusBody() {
        if (!ready || !options.editing || (!editor && !fallbackActive)) return;
        if (editor) editor.editing.view.focus();
        else fallback.focus();
    }
    function enterBody(event) {
        if (!options.editing || event.isComposing) return;
        event.preventDefault();
        titleFocusPending = false;
        focusBody();
    }
    title.addEventListener('keydown', event => { if (event.key === 'Enter') enterBody(event); });
    // Some Android keyboards send only beforeinput for their Next/Enter action.
    title.addEventListener('beforeinput', event => {
        if (event.inputType === 'insertParagraph' || event.inputType === 'insertLineBreak') enterBody(event);
    });
    function focusForEditing() {
        if (!ready || !options.editing || (!editor && !fallbackActive) || editingFocusApplied) return false;
        editingFocusApplied = true;
        if (titleFocusPending) {
            titleFocusPending = false;
            title.focus(); title.setSelectionRange(title.value.length, title.value.length);
        } else focusBody();
        return true;
    }
    document.addEventListener('input', event => {
        if (event.target.matches('.ck-source-editing-area textarea')) { dirty = true; flush(); }
    }, true);
    document.addEventListener('focusin', event => {
        document.body.classList.toggle('body-focus', event.target !== title);
    });
    document.addEventListener('click', event => {
        const link = event.target.closest('a[href], [data-url], oembed[url], [data-pocket-embed-url]');
        if (link && (!options.editing || preview.contains(link))) {
            event.preventDefault(); event.stopPropagation();
            const url = link.getAttribute('href') || link.getAttribute('data-url') || link.getAttribute('url') || link.getAttribute('data-pocket-embed-url');
            if (/^#root\/[A-Za-z0-9_\/-]+$/.test(url)) PocketNative.openNote(url);
            else if (url.startsWith('#')) {
                try { document.getElementById(decodeURIComponent(url.slice(1)))?.scrollIntoView({behavior:'smooth'}); } catch (_) { }
            } else PocketNative.openLink(url);
        }
    }, true);
    function configure(next) {
        const wasEditing = !!options.editing;
        if (wasEditing && !next.editing) flush();
        options = next || {};
        for (const key of ['background','foreground','muted','surface','border','accent']) {
            if (options[key]) document.documentElement.style.setProperty('--' + key, options[key]);
        }
        document.body.classList.toggle('editing', !!options.editing);
        document.body.classList.toggle('keyboard-visible', !!options.keyboardVisible);
        title.readOnly = !options.editing || (!editor && !fallbackActive);
        title.placeholder = options.editing ? 'Название' : 'Без названия';
        fallback.readOnly = !options.editing;
        if (!options.editing) editingFocusApplied = false;
        if (editor) {
            if (options.editing) editor.disableReadOnlyMode('native');
            else {
                const plugin = editor.plugins.get('SourceEditing');
                if (plugin.isSourceEditingMode) plugin.isSourceEditingMode = false;
                editor.enableReadOnlyMode('native');
            }
        }
        if (options.editing && !editor && !editorPromise && !fallbackActive) initializeEditor();
        resizeTitle();
    }
    window.pocket = { configure, flush, snapshot, focusForEditing, get editor() { return editor; },
        get ready() { return ready; }, get editorInitializing() { return !!editorPromise && !editor && !fallbackActive; } };
    function loadEditorLibrary() {
        if (editorLibraryPromise) return editorLibraryPromise;
        function loadScript(src) {
            return new Promise((resolve, reject) => {
                const script = document.createElement('script');
                script.src = src;
                script.onload = resolve;
                script.onerror = () => reject(new Error('Editor library could not be loaded'));
                document.head.append(script);
            });
        }
        editorLibraryPromise = (async () => {
            if (!window.CKEDITOR) await loadScript('vendor/ckeditor5.umd.js');
            if (!window.CKEDITOR_TRANSLATIONS?.ru) await loadScript('vendor/ru.umd.js');
            return window.CKEDITOR;
        })();
        return editorLibraryPromise;
    }
    function createEditor(C) {
        class PocketBoundary extends C.Plugin {
            afterInit() {
                const instance = this.editor;
                const nativeToView = instance.data.processor.toView.bind(instance.data.processor);
                if (isMarkdown) {
                    const html = new C.HtmlDataProcessor(instance.data.viewDocument);
                    const nativeToData = instance.data.processor.toData.bind(instance.data.processor);
                    instance.data.processor.toData = view => {
                        const doc = parsed(html.toData(view));
                        const raw = new Map();
                        doc.body.querySelectorAll('[data-pocket-preserved],[data-pocket-attributes]').forEach(node => {
                            if (!doc.body.contains(node)) return;
                            const id = 'POCKETRAW' + token().replace(/[^a-z0-9]/gi, '');
                            raw.set(id, forSaving(node.outerHTML)); node.replaceWith(doc.createTextNode(id));
                        });
                        let output = nativeToData(html.toView(doc.body.innerHTML));
                        raw.forEach((fragment, id) => { output = output.split(id).join(fragment); });
                        return output;
                    };
                    const clipboard = instance.data.htmlProcessor;
                    const clipboardToView = clipboard.toView.bind(clipboard);
                    clipboard.toView = raw => clipboardToView(forEditing(raw, false));
                    const markdownInput = new C.MarkdownGfmMdToHtml();
                    instance.data.processor.toView = raw => html.toView(forEditing(markdownInput.parse(raw), false));
                } else instance.data.processor.toView = html => nativeToView(forEditing(html));
                instance.conversion.for('editingDowncast').add(dispatcher => {
                    function imageUnder(element) {
                        if (!element) return null;
                        if (element.is('element','img')) return element;
                        if (element.getChildren) for (const child of element.getChildren()) {
                            const found = imageUnder(child); if (found) return found;
                        }
                        return null;
                    }
                    for (const model of ['imageBlock','imageInline']) dispatcher.on('attribute:src:' + model, (event, data, api) => {
                        const image = imageUnder(api.mapper.toViewElement(data.item));
                        if (image && data.attributeNewValue != null) api.writer.setAttribute('src',resourceUrl(data.attributeNewValue),image);
                    }, {priority:'lowest'});
                });
            }
        }
        const plugins = ['Essentials','Paragraph','Bold','Italic','Underline','Strikethrough','Subscript','Superscript',
            'Heading','List','ListProperties','TodoList','Indent','IndentBlock','BlockQuote','Code','CodeBlock','Link','AutoLink',
            'Autoformat','HorizontalLine','Image','ImageCaption','ImageStyle','ImageToolbar','ImageInsertViaUrl',
            'Table','TableToolbar','TableCaption','TableProperties','TableCellProperties','GeneralHtmlSupport','SourceEditing',
            'FontColor','FontBackgroundColor','FontSize','FontFamily','Alignment','RemoveFormat','PasteFromOffice','HtmlComment']
            .map(name => C[name]).filter(Boolean);
        plugins.push(PocketBoundary);
        if (isMarkdown) plugins.push(C.Markdown);
        return C.ClassicEditor.create(document.getElementById('editor'), {
            licenseKey:'GPL', plugins, language:'ru', translations:[CKEDITOR_TRANSLATIONS],
            initialData:isMarkdown ? initial.markdown : initial.html,
            toolbar:{items:['undo','redo','|','bold','italic','heading','|','bulletedList','numberedList','todoList',
                '|','link','insertTable','insertImageViaUrl','blockQuote','codeBlock','horizontalLine',
                ...(isMarkdown ? [] : ['|','fontColor','fontBackgroundColor','alignment']), 'outdent','indent','sourceEditing'],shouldNotGroupWhenFull:false},
            image:{toolbar:['imageTextAlternative','toggleImageCaption','imageStyle:inline','imageStyle:block']},
            table:{contentToolbar:isMarkdown ? ['tableColumn','tableRow','mergeTableCells'] : ['tableColumn','tableRow','mergeTableCells','tableProperties','tableCellProperties']},
            heading:{options:[{model:'paragraph',title:'Текст',class:'ck-heading_paragraph'}, ...[1,2,3,4,5,6].map(n =>
                ({model:'heading'+n,view:'h'+n,title:'Заголовок '+n,class:'ck-heading_heading'+n}))]},
            htmlSupport:{allow:[{name:/.*/,attributes:true,classes:true,styles:true}]},
            link:{addTargetToExternalLinks:false,defaultProtocol:'https://'},
            list:{properties:{styles:true,startIndex:true,reversed:true}},
            root:{placeholder:'Текст заметки…'},
            codeBlock:{languages:[{language:'plaintext',label:'Текст'},{language:'javascript',label:'JavaScript'},
                {language:'python',label:'Python'},{language:'bash',label:'Shell'},{language:'json',label:'JSON'},
                {language:'html',label:'HTML'},{language:'css',label:'CSS'},{language:'sql',label:'SQL'}]},
        });
    }
    function initializeEditor() {
        if (editor || editorPromise || fallbackActive) return;
        // HTML reading never pays for parsing the editor bundle; create it only when editing is requested.
        editorPromise = loadEditorLibrary().then(createEditor).then(instance => {
            editor = instance;
            ready = true;
            editor.model.document.on('change:data', () => { dirty = true; flush(); });
            // Source editing may only update the model when exiting; input capture flushes its raw value immediately.
            const sourcePlugin = editor.plugins.get('SourceEditing');
            const updateSource = sourcePlugin.updateEditorData.bind(sourcePlugin);
            sourcePlugin.updateEditorData = (...args) => {
                applyingSource = true;
                try { return updateSource(...args); }
                finally { applyingSource = false; dirty = false; }
            };
            sourcePlugin.on('change:isSourceEditingMode', () => {
                queueMicrotask(() => { const area = sourceArea(); if (area && !isMarkdown) area.value = forSaving(area.value); });
            });
            configure(options);
            preview.hidden = true;
            preview.replaceChildren();
            preview.classList.remove('ck-content');
            editorHost.hidden = false;
            resizeTitle();
            editor.ui.update();
            PocketNative.editorReady();
        }).catch(showFallback);
    }
    function showFallback(error) {
        window.pocket.failure = String(error.name || 'Error') + ':' + String(error.message || '').split(/[\n{]/)[0].slice(0,100);
        // An initialization failure still offers exact source; opening it never reports an edit.
        editorHost.hidden = true;
        preview.hidden = true;
        document.getElementById('error').hidden = false;
        fallbackActive = true;
        fallback.hidden = false; fallback.value = lastHtml; ready = true;
        fallback.addEventListener('input', flush); configure(options);
        PocketNative.editorReady();
    }
    function showPreview(html) {
        // forEditing removes active content and rewrites binary URLs through the existing native boundary.
        preview.innerHTML = forEditing(html, true, true);
        // The bundled Markdown parser removes task-list classes. Mark only the
        // preview; the original Markdown and editing pipeline remain untouched.
        if (isMarkdown) preview.querySelectorAll('li input[type="checkbox"]').forEach(input => {
            input.closest('li').classList.add('pocket-task-item');
        });
        configure(options);
        if (!options.editing) { ready = true; PocketNative.editorReady(); }
    }
    if (isMarkdown) {
        // Reuse the bundled Markdown parser rather than introducing a second renderer.
        loadEditorLibrary().then(C => showPreview(new C.MarkdownGfmMdToHtml().parse(initial.markdown))).catch(showFallback);
    } else {
        try { showPreview(initial.html); } catch (error) { showFallback(error); }
    }
    window.addEventListener('pagehide', flush);
})();
