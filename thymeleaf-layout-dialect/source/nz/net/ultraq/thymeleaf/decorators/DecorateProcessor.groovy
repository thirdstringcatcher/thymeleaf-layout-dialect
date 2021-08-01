/* 
 * Copyright 2012, Emanuel Rabina (http://www.ultraq.net.nz/)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nz.net.ultraq.thymeleaf.decorators

import nz.net.ultraq.thymeleaf.decorators.html.HtmlDocumentDecorator
import nz.net.ultraq.thymeleaf.decorators.xml.XmlDocumentDecorator
import nz.net.ultraq.thymeleaf.expressions.ExpressionProcessor
import nz.net.ultraq.thymeleaf.fragments.FragmentFinder
import nz.net.ultraq.thymeleaf.models.TemplateModelFinder

import org.thymeleaf.context.IContext
import org.thymeleaf.context.ITemplateContext
import org.thymeleaf.engine.AttributeName
import org.thymeleaf.model.IModel
import org.thymeleaf.model.IProcessableElementTag
import org.thymeleaf.processor.element.AbstractAttributeModelProcessor
import org.thymeleaf.processor.element.IElementModelStructureHandler
import org.thymeleaf.standard.StandardDialect
import org.thymeleaf.templatemode.TemplateMode

/**
 * Specifies the name of the template to decorate using the current template.
 * 
 * @author Emanuel Rabina
 */
class DecorateProcessor extends AbstractAttributeModelProcessor {

	static final String PROCESSOR_NAME = 'decorate'
	static final int PROCESSOR_PRECEDENCE = 0

	private final boolean autoHeadMerging
	private final SortingStrategy sortingStrategy

	/**
	 * Constructor, configure this processor to work on the 'decorate' attribute
	 * and to use the given sorting strategy.
	 * 
	 * @param templateMode
	 * @param dialectPrefix
	 * @param sortingStrategy
	 * @param autoHeadMerging
	 */
	DecorateProcessor(TemplateMode templateMode, String dialectPrefix, SortingStrategy sortingStrategy,
		boolean autoHeadMerging) {

		this(templateMode, dialectPrefix, sortingStrategy, autoHeadMerging, PROCESSOR_NAME)
	}

	/**
	 * Constructor, configurable processor name for the purposes of the
	 * deprecated {@code layout:decorator} alias.
	 * 
	 * @param templateMode
	 * @param dialectPrefix
	 * @param sortingStrategy
	 * @param autoHeadMerging
	 * @param attributeName
	 */
	protected DecorateProcessor(TemplateMode templateMode, String dialectPrefix, SortingStrategy sortingStrategy,
		boolean autoHeadMerging, String attributeName) {

		super(templateMode, dialectPrefix, null, false, attributeName, true, PROCESSOR_PRECEDENCE, false)

		this.sortingStrategy = sortingStrategy
		this.autoHeadMerging = autoHeadMerging
	}

	/**
	 * Locates the template to decorate and, once decorated, inserts it into the
	 * processing chain.
	 * 
	 * @param context
	 * @param model
	 * @param attributeName
	 * @param attributeValue
	 * @param structureHandler
	 */
	@Override
	protected void doProcess(ITemplateContext context, IModel model, AttributeName attributeName,
		String attributeValue, IElementModelStructureHandler structureHandler) {

		def templateModelFinder = new TemplateModelFinder(context)

		// Load the entirety of this template so we can access items outside of the root element
		def contentTemplateName = context.templateData.template
		def contentTemplate = templateModelFinder.findTemplate(contentTemplateName).cloneModel()

		// Check that the root element is the same as the one currently being processed
		def contentRootEvent = contentTemplate.find { event -> event instanceof IProcessableElementTag }
		def rootElement = model.first()
		if (!rootElementsEqual(contentRootEvent, rootElement, context)) {
			throw new IllegalArgumentException('layout:decorate/data-layout-decorate must appear in the root element of your template')
		}

		// Remove the decorate processor from the root element
		if (rootElement.hasAttribute(attributeName)) {
			rootElement = context.modelFactory.removeAttribute(rootElement, attributeName)
			model.replace(0, rootElement)
		}
		contentTemplate.replaceModel(contentTemplate.findIndexOf { event -> event instanceof IProcessableElementTag }, model)

		// Locate the template to decorate
		def decorateTemplateExpression = new ExpressionProcessor(context).parseFragmentExpression(attributeValue)
		def decorateTemplate = templateModelFinder.findTemplate(decorateTemplateExpression)
		def decorateTemplateData = decorateTemplate.templateData
		decorateTemplate = decorateTemplate.cloneModel()

		// Gather all fragment parts from this page to apply to the new document
		// after decoration has taken place
		def pageFragments = new FragmentFinder(dialectPrefix).findFragments(model)

		// Choose the decorator to use based on template mode, then apply it
		def decorator =
			templateMode == TemplateMode.HTML ? new HtmlDocumentDecorator(context, sortingStrategy, autoHeadMerging) :
			templateMode == TemplateMode.XML  ? new XmlDocumentDecorator(context) :
			null
		if (!decorator) {
			throw new IllegalArgumentException(
				"Layout dialect cannot be applied to the ${templateMode} template mode, only HTML and XML template modes are currently supported"
			)
		}
		def resultTemplate = decorator.decorate(decorateTemplate, contentTemplate)
		model.replaceModel(0, resultTemplate)
		structureHandler.templateData = decorateTemplateData

		// Save layout fragments for use later by layout:fragment processors
		structureHandler.setLocalFragmentCollection(context, pageFragments, true)

		// Scope variables in fragment definition to template.  Parameters *must* be
		// named as there is no mechanism for setting their name at the target
		// layout/template.
		if (decorateTemplateExpression.hasParameters()) {
			if (decorateTemplateExpression.hasSyntheticParameters()) {
				throw new IllegalArgumentException('Fragment parameters must be named when used with layout:decorate/data-layout-decorate')
			}
			decorateTemplateExpression.parameters.each { parameter ->
				structureHandler.setLocalVariable(parameter.left.execute(context), parameter.right.execute(context))
			}
		}
	}

	/**
	 * Compare the root elements, barring some attributes, to see if they are the
	 * same.
	 * 
	 * @param root1
	 * @param root2
	 * @param context
	 * @return {@code true} if the elements share the same name and all attributes,
	 *         with the exception of XML namespace declarations and Thymeleaf's
	 *         {@code th:with} attribute processor.
	 */
	private static boolean rootElementsEqual(IProcessableElementTag element1,
		IProcessableElementTag element2, IContext context) {

		if (element1 instanceof IProcessableElementTag && element2 instanceof IProcessableElementTag &&
			element1.elementDefinition == element2.elementDefinition) {
			def difference = element1.attributeMap - element2.attributeMap
			return difference.size() == 0 || difference
				.collect { key, value -> key.startsWith('xmlns:') || key == "${context.getPrefixForDialect(StandardDialect)}:with" }
				.inject { result, item -> result && item }
		}
		return false
	}
}
