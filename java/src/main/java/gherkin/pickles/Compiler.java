package gherkin.pickles;

import gherkin.SymbolCounter;
import gherkin.ast.Background;
import gherkin.ast.DataTable;
import gherkin.ast.DocString;
import gherkin.ast.Examples;
import gherkin.ast.Feature;
import gherkin.ast.GherkinDocument;
import gherkin.ast.Location;
import gherkin.ast.Node;
import gherkin.ast.Scenario;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.ScenarioOutline;
import gherkin.ast.Step;
import gherkin.ast.TableCell;
import gherkin.ast.TableRow;
import gherkin.ast.Tag;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;

public class Compiler {

    public List<Pickle> compile(GherkinDocument gherkinDocument) {
        List<Pickle> pickles = new ArrayList<>();
        Feature feature = gherkinDocument.getFeature();
        if (feature == null) {
            return pickles;
        }

        List<Tag> featureTags = feature.getTags();
        List<PickleStep> backgroundSteps = new ArrayList<>();

        for (ScenarioDefinition scenarioDefinition : feature.getChildren()) {
            if (scenarioDefinition instanceof Background) {
                backgroundSteps = pickleSteps(scenarioDefinition);
            } else if (scenarioDefinition instanceof Scenario) {
                compileScenario(pickles, backgroundSteps, (Scenario) scenarioDefinition, featureTags);
            } else {
                compileScenarioOutline(pickles, backgroundSteps, (ScenarioOutline) scenarioDefinition, featureTags);
            }
        }
        return pickles;
    }

    private void compileScenario(List<Pickle> pickles, List<PickleStep> backgroundSteps, Scenario scenario, List<Tag> featureTags) {
        if (scenario.getSteps().isEmpty())
            return;

        List<PickleStep> steps = new ArrayList<>();
        steps.addAll(backgroundSteps);

        List<Tag> scenarioTags = new ArrayList<>();
        scenarioTags.addAll(featureTags);
        scenarioTags.addAll(scenario.getTags());

        steps.addAll(pickleSteps(scenario));

        Pickle pickle = new Pickle(
                scenario.getName(),
                steps,
                pickleTags(scenarioTags),
                singletonList(pickleLocation(scenario.getLocation()))
        );
        pickles.add(pickle);
    }

    private void compileScenarioOutline(List<Pickle> pickles, List<PickleStep> backgroundSteps, ScenarioOutline scenarioOutline, List<Tag> featureTags) {
        if (scenarioOutline.getSteps().isEmpty())
            return;

        for (final Examples examples : scenarioOutline.getExamples()) {
            if (examples.getTableHeader() == null) continue;
            List<TableCell> variableCells = examples.getTableHeader().getCells();
            for (final TableRow values : examples.getTableBody()) {
                List<TableCell> valueCells = values.getCells();

                List<PickleStep> steps = new ArrayList<>();
                steps.addAll(backgroundSteps);

                List<Tag> tags = new ArrayList<>();
                tags.addAll(featureTags);
                tags.addAll(scenarioOutline.getTags());
                tags.addAll(examples.getTags());

                for (Step scenarioOutlineStep : scenarioOutline.getSteps()) {
                    String stepText = interpolate(scenarioOutlineStep.getText(), variableCells, valueCells);

                    // TODO: Use an Array of location in DataTable/DocString as well.
                    // If the Gherkin AST classes supported
                    // a list of locations, we could just reuse the same classes

                    PickleStep pickleStep = new PickleStep(
                            stepText,
                            createPickleArguments(scenarioOutlineStep.getArgument(), variableCells, valueCells),
                            asList(
                                    pickleLocation(values.getLocation()),
                                    pickleStepLocation(scenarioOutlineStep)
                            )
                    );
                    steps.add(pickleStep);
                }

                Pickle pickle = new Pickle(
                        interpolate(scenarioOutline.getName(), variableCells, valueCells),
                        steps,
                        pickleTags(tags),
                        asList(
                                pickleLocation(values.getLocation()),
                                pickleLocation(scenarioOutline.getLocation())
                        )
                );

                pickles.add(pickle);
            }
        }
    }

    private List<Argument> createPickleArguments(Node argument) {
        List<TableCell> noCells = emptyList();
        return createPickleArguments(argument, noCells, noCells);
    }

    private List<Argument> createPickleArguments(Node argument, List<TableCell> variableCells, List<TableCell> valueCells) {
        List<Argument> result = new ArrayList<>();
        if (argument == null) return result;
        if (argument instanceof DataTable) {
            DataTable t = (DataTable) argument;
            List<TableRow> rows = t.getRows();
            List<PickleRow> newRows = new ArrayList<>(rows.size());
            for (TableRow row : rows) {
                List<TableCell> cells = row.getCells();
                List<PickleCell> newCells = new ArrayList<>();
                for (TableCell cell : cells) {
                    newCells.add(
                            new PickleCell(
                                    pickleLocation(cell.getLocation()),
                                    interpolate(cell.getValue(), variableCells, valueCells)
                            )
                    );
                }
                newRows.add(new PickleRow(newCells));
            }
            result.add(new PickleTable(newRows));
        } else if (argument instanceof DocString) {
            DocString ds = (DocString) argument;
            result.add(
                    new PickleString(
                            pickleLocation(ds.getLocation()),
                            interpolate(ds.getContent(), variableCells, valueCells)
                    )
            );
        } else {
            throw new RuntimeException("Unexpected argument type: " + argument);
        }
        return result;
    }

    private List<PickleStep> pickleSteps(ScenarioDefinition scenarioDefinition) {
        List<PickleStep> result = new ArrayList<>();
        for (Step step : scenarioDefinition.getSteps()) {
            result.add(pickleStep(step));
        }
        return unmodifiableList(result);
    }

    private PickleStep pickleStep(Step step) {
        return new PickleStep(
                step.getText(),
                createPickleArguments(step.getArgument()),
                singletonList(pickleStepLocation(step))
        );
    }

    private String interpolate(String name, List<TableCell> variableCells, List<TableCell> valueCells) {
        int col = 0;
        for (TableCell variableCell : variableCells) {
            TableCell valueCell = valueCells.get(col++);
            String header = variableCell.getValue();
            String value = valueCell.getValue();
            name = name.replace("<" + header + ">", value);
        }
        return name;
    }

    private PickleLocation pickleStepLocation(Step step) {
        return new PickleLocation(
                step.getLocation().getLine(),
                step.getLocation().getColumn() + (step.getKeyword() != null ? SymbolCounter.countSymbols(step.getKeyword()) : 0)
        );
    }

    private PickleLocation pickleLocation(Location location) {
        return new PickleLocation(location.getLine(), location.getColumn());
    }

    private List<PickleTag> pickleTags(List<Tag> tags) {
        List<PickleTag> result = new ArrayList<>();
        for (Tag tag : tags) {
            result.add(pickleTag(tag));
        }
        return result;
    }

    private PickleTag pickleTag(Tag tag) {
        return new PickleTag(pickleLocation(tag.getLocation()), tag.getName());
    }
}
